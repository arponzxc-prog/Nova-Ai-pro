package com.example.camerapill

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.assistant.OverlayLifecycleOwner
import com.example.assistant.overlayWindowType
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * WindowManager host for the Camera Pill. Single instance per process (owned by CameraPillController),
 * so duplicate overlays are impossible. Non-touchable and non-focusable: it never steals input
 * and never touches the physical camera.
 */
class CameraPillOverlay(
    context: Context,
    private val uiFlow: StateFlow<CameraPillUiModel>,
    private val micLevelFlow: StateFlow<Float>,
    private val themeModeProvider: () -> String
) {
    private val context = context.applicationContext
    private val windowManager = this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastSizeScale = -1f

    // Offset is read by the composable through this state holder so repositioning does not recreate the view.
    private val offsetState = androidx.compose.runtime.mutableIntStateOf(0)

    val isShowing: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(sizeScale: Float): Boolean {
        if (view != null) {
            reposition(sizeScale)
            return true
        }
        if (!Settings.canDrawOverlays(context)) return false

        val density = context.resources.displayMetrics.density
        val winW = ((PillDimens.expandedWidth.value * sizeScale + PillDimens.glowPadding.value * 2) * density).toInt()
        val winH = ((PillDimens.expandedHeight.value * sizeScale + PillDimens.glowPadding.value * 2) * density).toInt()

        val lp = WindowManager.LayoutParams(
            winW, winH, overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "NovaCameraPill"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyPosition(lp, sizeScale)

        val lifecycleOwner = OverlayLifecycleOwner().also { it.attach() }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val ui: State<CameraPillUiModel> = uiFlow.collectAsState()
                val mic: State<Float> = micLevelFlow.collectAsState()
                MyApplicationTheme(themeMode = themeModeProvider()) {
                    CameraPillContent(ui = ui, micLevel = mic, contentOffsetXPx = offsetState.intValue)
                }
            }
        }

        return runCatching {
            windowManager.addView(composeView, lp)
            view = composeView
            owner = lifecycleOwner
            params = lp
            lastSizeScale = sizeScale
            // API 28-29 only expose DisplayCutout through an attached view: re-place once attached.
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P until Build.VERSION_CODES.R) {
                composeView.post { reposition(sizeScale) }
            }
            true
        }.getOrElse {
            lifecycleOwner.detach()
            false
        }
    }

    /** Recompute placement (rotation, size change, display change). */
    fun reposition(sizeScale: Float) {
        val v = view ?: return
        val lp = params ?: return
        val density = context.resources.displayMetrics.density
        lp.width = ((PillDimens.expandedWidth.value * sizeScale + PillDimens.glowPadding.value * 2) * density).toInt()
        lp.height = ((PillDimens.expandedHeight.value * sizeScale + PillDimens.glowPadding.value * 2) * density).toInt()
        applyPosition(lp, sizeScale)
        runCatching { windowManager.updateViewLayout(v, lp) }
        lastSizeScale = sizeScale
    }

    private fun applyPosition(lp: WindowManager.LayoutParams, sizeScale: Float) {
        val density = context.resources.displayMetrics.density
        val (screenW, screenH, cutouts, statusTop) = readDisplayGeometry()
        val glow = (PillDimens.glowPadding.value * density).toInt()
        val pos = CutoutPlacement.compute(
            screenWidth = screenW,
            screenHeight = screenH,
            cutouts = cutouts,
            statusBarTop = statusTop,
            collapsedWidth = (PillDimens.collapsedWidth.value * sizeScale * density).toInt(),
            collapsedHeight = (PillDimens.collapsedHeight.value * sizeScale * density).toInt(),
            windowWidth = lp.width,
            windowHeight = lp.height,
            gapPx = (6 * density).toInt(),
            minTopPx = (4 * density).toInt()
        )
        lp.x = pos.x
        // May be slightly negative: only the transparent glow margin goes off-screen (FLAG_LAYOUT_NO_LIMITS).
        lp.y = pos.y - glow
        offsetState.intValue = pos.contentOffsetX
    }

    private data class DisplayGeometry(val w: Int, val h: Int, val cutouts: List<PxRect>, val statusTop: Int)

    private fun readDisplayGeometry(): DisplayGeometry {
        val dm = context.resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        var statusTop = 0
        val rects = mutableListOf<PxRect>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = windowManager.maximumWindowMetrics // full display incl. cutouts, current rotation
                w = metrics.bounds.width()
                h = metrics.bounds.height()
                val insets = metrics.windowInsets
                statusTop = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
                insets.displayCutout?.boundingRects?.forEach { rects += PxRect(it.left, it.top, it.right, it.bottom) }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                // API 28-29: cutout is only exposed through an attached view's root insets.
                val cutout = view?.rootWindowInsets?.displayCutout
                cutout?.boundingRects?.forEach { rects += PxRect(it.left, it.top, it.right, it.bottom) }
                statusTop = view?.rootWindowInsets?.stableInsetTop ?: 0
            }
        }
        if (statusTop == 0) {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) statusTop = context.resources.getDimensionPixelSize(resId)
        }
        return DisplayGeometry(w, h, rects, statusTop)
    }

    fun hide() {
        val v = view
        view = null
        if (v != null) runCatching { windowManager.removeViewImmediate(v) }
        owner?.detach()
        owner = null
        params = null
    }
}
