package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.AiAssistantApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * স্ক্রিন পড়া, বাটন ট্যাপ, টেক্সট টাইপ, জেসচার ও Android 11+ স্ক্রিনশট নেওয়ার Accessibility Service।
 * ইউজার নিজে স্ক্রিনে টাচ করলে চলমান UI অটোমেশন সাথে সাথে থামিয়ে দেয়।
 */
class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AssistantAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _screenChangeEvents = MutableSharedFlow<Long>(extraBufferCapacity = 32)
        val screenChangeEvents = _screenChangeEvents.asSharedFlow()

        @Volatile
        var currentPackageName: String = ""
            private set

        @Volatile
        var currentActivityName: String = ""
            private set

        @Volatile
        var isBotPerformingAction: Boolean = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var uiIndicatorOverlay: UiAgentIndicatorOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true

        val app = applicationContext as? AiAssistantApplication
        if (app != null) {
            uiIndicatorOverlay = UiAgentIndicatorOverlay(
                context = this,
                controller = app.assistantController,
                themeModeProvider = { "system" },
                useAccessibilityOverlay = true
            )
            serviceScope.launch {
                app.repository.saveAccessibilityWasEnabled(true)
            }
            serviceScope.launch {
                app.assistantController.state.collect { rtState ->
                    val stopEnabled = app.repository.uiFloatingStopEnabledFlow.first()
                    if (rtState.isUiAutomationRunning && stopEnabled) {
                        uiIndicatorOverlay?.show()
                    } else {
                        uiIndicatorOverlay?.hide()
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg.isNotBlank()) currentPackageName = pkg
                if (cls.isNotBlank() && !cls.startsWith("android.widget") && !cls.startsWith("android.view")) {
                    currentActivityName = cls
                }
                _screenChangeEvents.tryEmit(System.currentTimeMillis())
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg.isNotBlank() && currentPackageName.isBlank()) {
                    currentPackageName = pkg
                }
                _screenChangeEvents.tryEmit(System.currentTimeMillis())
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // AI কাজ করার সময় ইউজার নিজে অন্য কোনো অ্যাপে/স্ক্রিনে টাচ করলে অটো-থামানো
                val ctrl = (applicationContext as? AiAssistantApplication)?.assistantController
                val isOurOverlayPkg = pkg == applicationContext.packageName
                if (ctrl != null && ctrl.state.value.isUiAutomationRunning && !isBotPerformingAction && !isOurOverlayPkg) {
                    ctrl.requestStopUiAgent(byTouch = true)
                }
            }
        }
    }

    override fun onInterrupt() {
        // সিস্টেম দ্বারা সাময়িক বাধা
    }

    override fun onUnbind(intent: Intent?): Boolean {
        uiIndicatorOverlay?.hide()
        if (instance === this) instance = null
        _isConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        uiIndicatorOverlay?.hide()
        uiIndicatorOverlay = null
        if (instance === this) instance = null
        _isConnected.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    fun readCurrentScreen(policy: AppAccessPolicy): SnapshotBuildResult {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()?.takeIf { it.isNotBlank() } ?: currentPackageName
        val result = ScreenSnapshotBuilder.buildFromRoot(
            root = root,
            packageName = pkg,
            activityName = currentActivityName,
            policy = policy
        )
        if (root != null) {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
        return result
    }

    /**
     * স্থির sleep না দিয়ে Accessibility উইন্ডো-চেঞ্জ ইভেন্টের জন্য অপেক্ষা করে (debounce ও timeout সহ)।
     */
    suspend fun awaitScreenChange(timeoutMs: Long = 1800L, debounceMs: Long = 220L): Boolean {
        val changed = withTimeoutOrNull(timeoutMs.coerceIn(300L, 8000L)) {
            screenChangeEvents.first()
            true
        } ?: false
        if (changed) {
            delay(debounceMs)
        }
        return changed
    }

    suspend fun clickElement(nodeRef: SnapshotNodeRef, policy: AppAccessPolicy): Boolean {
        val access = ScreenSecurityGuard.checkAppAccess(currentPackageName.ifBlank { null }, policy)
        if (!access.allowed) return false

        isBotPerformingAction = true
        return try {
            val root = rootInActiveWindow
            var clicked = false
            if (root != null) {
                try {
                    val liveNode = findMatchingNode(root, nodeRef)
                    if (liveNode != null) {
                        var target: AccessibilityNodeInfo? = liveNode
                        var depth = 0
                        while (target != null && depth < 5) {
                            if (target.isClickable) {
                                clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                if (clicked) break
                            }
                            val parent = target.parent
                            if (target !== liveNode) {
                                @Suppress("DEPRECATION")
                                runCatching { target.recycle() }
                            }
                            target = parent
                            depth++
                        }
                        if (target != null && target !== liveNode) {
                            @Suppress("DEPRECATION")
                            runCatching { target.recycle() }
                        }
                        @Suppress("DEPRECATION")
                        runCatching { liveNode.recycle() }
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    runCatching { root.recycle() }
                }
            }

            if (!clicked && !nodeRef.bounds.isEmpty) {
                clicked = dispatchTapGesture(
                    nodeRef.bounds.centerX().toFloat(),
                    nodeRef.bounds.centerY().toFloat()
                )
            }
            clicked
        } finally {
            delay(180L)
            isBotPerformingAction = false
        }
    }

    suspend fun typeText(nodeRef: SnapshotNodeRef?, text: String, policy: AppAccessPolicy): Boolean {
        val access = ScreenSecurityGuard.checkAppAccess(currentPackageName.ifBlank { null }, policy)
        if (!access.allowed) return false

        isBotPerformingAction = true
        return try {
            val root = rootInActiveWindow ?: return false
            try {
                val targetNode = if (nodeRef != null) {
                    findMatchingNode(root, nodeRef)
                } else {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root)
                } ?: return false

                try {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } finally {
                    @Suppress("DEPRECATION")
                    runCatching { targetNode.recycle() }
                }
            } finally {
                @Suppress("DEPRECATION")
                runCatching { root.recycle() }
            }
        } finally {
            delay(150L)
            isBotPerformingAction = false
        }
    }

    suspend fun scrollScreen(direction: String, policy: AppAccessPolicy): Boolean {
        val access = ScreenSecurityGuard.checkAppAccess(currentPackageName.ifBlank { null }, policy)
        if (!access.allowed) return false

        isBotPerformingAction = true
        return try {
            val dir = direction.lowercase()
            val root = rootInActiveWindow
            var done = false
            if (root != null) {
                try {
                    val scrollable = findFirstScrollable(root)
                    if (scrollable != null) {
                        val action = if (dir == "up" || dir == "left") {
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        } else {
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        }
                        done = scrollable.performAction(action)
                        @Suppress("DEPRECATION")
                        runCatching { scrollable.recycle() }
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    runCatching { root.recycle() }
                }
            }
            if (!done) {
                done = performDirectionalSwipe(dir)
            }
            done
        } finally {
            delay(160L)
            isBotPerformingAction = false
        }
    }

    suspend fun performDirectionalSwipe(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        return when (direction.lowercase()) {
            "down" -> dispatchSwipeGesture(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f)
            "up" -> dispatchSwipeGesture(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.72f)
            "left" -> dispatchSwipeGesture(w * 0.82f, h * 0.5f, w * 0.18f, h * 0.5f)
            "right" -> dispatchSwipeGesture(w * 0.18f, h * 0.5f, w * 0.82f, h * 0.5f)
            else -> false
        }
    }

    suspend fun dispatchTapGesture(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    /**
     * Long-press at ([x],[y]) (used by automation recipes, e.g. selecting a chat bubble). A single-point stroke held
     * for [durationMs] is the platform's long-press; a zero-length lineTo is deliberately not used.
     */
    suspend fun dispatchLongPress(x: Float, y: Float, durationMs: Long = 700L): Boolean {
        isBotPerformingAction = true
        return try {
            val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(400L, 1500L))
            dispatchGestureAwait(GestureDescription.Builder().addStroke(stroke).build())
        } finally {
            delay(140L)
            isBotPerformingAction = false
        }
    }

    suspend fun dispatchSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L
    ): Boolean {
        isBotPerformingAction = true
        return try {
            val path = Path().apply {
                moveTo(startX.coerceAtLeast(1f), startY.coerceAtLeast(1f))
                lineTo(endX.coerceAtLeast(1f), endY.coerceAtLeast(1f))
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(120L, 1200L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGestureAwait(gesture)
        } finally {
            delay(140L)
            isBotPerformingAction = false
        }
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        if (!dispatched) return false
        return withTimeoutOrNull(2000L) { deferred.await() } ?: false
    }

    fun executeGlobalAction(action: String): Boolean {
        val code = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(code)
    }

    /**
     * Android 11+ (API 30+) এ ঐচ্ছিক স্ক্রিনশট ভিশন:
     * কেবল অনুমোদিত অ্যাপে ও ইন-মেমোরিতে JPEG Base64 তৈরি করে (কখনো ডিস্কে সেভ করে না)।
     */
    suspend fun captureScreenshotBase64(policy: AppAccessPolicy): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val access = ScreenSecurityGuard.checkAppAccess(currentPackageName.ifBlank { null }, policy)
        if (!access.allowed) return null

        val deferred = CompletableDeferred<String?>()
        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            if (hwBitmap == null) {
                                deferred.complete(null)
                                return
                            }
                            val soft = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap.recycle()
                            screenshot.hardwareBuffer.close()

                            val maxDim = 1024
                            val scale = minOf(1f, maxDim.toFloat() / maxOf(soft.width, soft.height))
                            val scaled = if (scale < 1f) {
                                Bitmap.createScaledBitmap(
                                    soft,
                                    (soft.width * scale).toInt().coerceAtLeast(1),
                                    (soft.height * scale).toInt().coerceAtLeast(1),
                                    true
                                ).also { soft.recycle() }
                            } else {
                                soft
                            }

                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
                            scaled.recycle()
                            deferred.complete(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                        } catch (_: Exception) {
                            deferred.complete(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        deferred.complete(null)
                    }
                }
            )
        }.onFailure {
            deferred.complete(null)
        }
        return withTimeoutOrNull(3500L) { deferred.await() }
    }

    private fun findMatchingNode(root: AccessibilityNodeInfo, target: SnapshotNodeRef): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        var visited = 0

        fun dfs(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || visited > 1200) return
            visited++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            var score = 0
            if (!target.viewId.isNullOrBlank() && node.viewIdResourceName == target.viewId) score += 4
            if (target.text.isNotBlank() && node.text?.toString()?.trim() == target.text) score += 3
            if (target.contentDesc.isNotBlank() && node.contentDescription?.toString()?.trim() == target.contentDesc) score += 3
            if (abs(rect.centerX() - target.bounds.centerX()) < 24 && abs(rect.centerY() - target.bounds.centerY()) < 24) {
                score += 4
            }
            if (score > bestScore && score >= 4) {
                @Suppress("DEPRECATION")
                best?.let { runCatching { it.recycle() } }
                best = AccessibilityNodeInfo.obtain(node)
                bestScore = score
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                try {
                    dfs(c, depth + 1)
                } finally {
                    @Suppress("DEPRECATION")
                    runCatching { c.recycle() }
                }
            }
        }
        dfs(root, 0)
        return best
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && root.isVisibleToUser) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            try {
                val found = findFirstEditable(child)
                if (found != null) return found
            } finally {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }
        return null
    }

    private fun findFirstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable && root.isVisibleToUser) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            try {
                val found = findFirstScrollable(child)
                if (found != null) return found
            } finally {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }
        return null
    }
}

object AccessibilityHelper {
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (AssistantAccessibilityService.isConnected.value) return true
        val expected = ComponentName(context, AssistantAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun isNotificationAccessEnabled(context: Context): Boolean {
        if (AssistantNotificationListenerService.isConnected.value) return true
        val expected = ComponentName(context, AssistantNotificationListenerService::class.java).flattenToString()
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAppInfoSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
