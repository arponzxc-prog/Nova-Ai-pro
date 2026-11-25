package com.example.assistant

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.R
import com.example.accessibility.UiAgentStatusPill
import com.example.tools.ContactMatch
import com.example.tools.PendingActionConfirmation
import com.example.tools.PendingContactSelection
import com.example.ui.components.ActionCardsBanner
import com.example.ui.screens.VoiceVisualizerOrb
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.VoiceModePhase
import kotlin.math.abs

/**
 * WindowManager ওভারলে-তে ComposeView চালানোর জন্য কাস্টম Lifecycle + SavedState মালিকানা।
 */
internal class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun attach() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun detach() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

internal fun overlayWindowType(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }
}

/**
 * স্ক্রিনের পাশে ভাসমান বাবল: টেনে সরানো যায়, কিনারায় স্ন্যাপ করে, আর ট্যাপ করলে ভয়েস সেশন শুরু হয়।
 */
class FloatingBubbleOverlay(
    private val context: Context,
    private val controller: AssistantRuntimeController,
    private val onBubbleTapped: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubbleView != null || !Settings.canDrawOverlays(context)) return

        val owner = OverlayLifecycleOwner().also { it.attach() }
        val dm = context.resources.displayMetrics
        val bubblePx = (58 * dm.density).toInt()

        val params = WindowManager.LayoutParams(
            bubblePx,
            bubblePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - bubblePx - (12 * dm.density).toInt()
            y = (dm.heightPixels * 0.42f).toInt()
        }

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val rtState by controller.state.collectAsStateWithLifecycle()
                MyApplicationTheme(themeMode = "system") {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = if (rtState.isEffectivelyPaused) {
                                        listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHighest,
                                            MaterialTheme.colorScheme.outline
                                        )
                                    } else {
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (rtState.isEffectivelyPaused) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = stringResource(R.string.bubble_toggle_title),
                            tint = if (rtState.isEffectivelyPaused) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }

        // ড্র্যাগ ও কিনারায় স্মুথ স্ন্যাপ টাচ লিসেনার
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = (initialX + dx).coerceIn(0, dm.widthPixels - bubblePx)
                    params.y = (initialY + dy).coerceIn(48, dm.heightPixels - bubblePx - 48)
                    runCatching { windowManager.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onBubbleTapped()
                    } else {
                        // ডান বা বাম কিনারায় স্ন্যাপ অ্যানিমেশন
                        val margin = (10 * dm.density).toInt()
                        val targetX = if (params.x + bubblePx / 2 < dm.widthPixels / 2) {
                            margin
                        } else {
                            dm.widthPixels - bubblePx - margin
                        }
                        ValueAnimator.ofInt(params.x, targetX).apply {
                            duration = 220L
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { anim ->
                                params.x = anim.animatedValue as Int
                                runCatching { if (bubbleView != null) windowManager.updateViewLayout(v, params) }
                            }
                            start()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            windowManager.addView(view, params)
            bubbleView = view
            lifecycleOwner = owner
            layoutParams = params
        }.onFailure {
            owner.detach()
        }
    }

    fun hide() {
        val v = bubbleView
        bubbleView = null
        if (v != null) {
            runCatching { windowManager.removeViewImmediate(v) }
        }
        lifecycleOwner?.detach()
        lifecycleOwner = null
        layoutParams = null
    }
}

/**
 * অন্য অ্যাপের ওপর ভয়েস সেশন চালানোর কমপ্যাক্ট ওভারলে কার্ড (Activity না খুলেই চলে)।
 */
class VoiceSessionOverlay(
    private val context: Context,
    private val controller: AssistantRuntimeController,
    private val themeModeProvider: () -> String,
    private val onOpenInApp: (String?) -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        if (overlayView != null || !Settings.canDrawOverlays(context)) return

        val owner = OverlayLifecycleOwner().also { it.attach() }
        val dm = context.resources.displayMetrics
        val horizontalMargin = (16 * dm.density).toInt()

        val params = WindowManager.LayoutParams(
            dm.widthPixels - horizontalMargin * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (28 * dm.density).toInt()
        }

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val rtState by controller.state.collectAsStateWithLifecycle()

                // SMS এডিট করার সময় কিবোর্ড ফোকাস নেওয়ার জন্য FLAG_NOT_FOCUSABLE ডাইনামিকভাবে সরানো
                val needKeyboardFocus = rtState.pendingConfirmation?.isSms == true
                LaunchedEffect(needKeyboardFocus) {
                    val v = overlayView ?: return@LaunchedEffect
                    val lp = this@VoiceSessionOverlay.layoutParams ?: return@LaunchedEffect
                    val newFlags = if (needKeyboardFocus) {
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    } else {
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    }
                    if (lp.flags != newFlags) {
                        lp.flags = newFlags
                        lp.dimAmount = if (needKeyboardFocus) 0.35f else 0f
                        runCatching { windowManager.updateViewLayout(v, lp) }
                    }
                }

                MyApplicationTheme(themeMode = themeModeProvider()) {
                    VoiceOverlayCardContent(
                        state = rtState,
                        onClose = { controller.onStopSession?.invoke() },
                        onStopUiAgent = { controller.requestStopUiAgent(byTouch = false) },
                        onOpenApp = { onOpenInApp(rtState.activeConversationId) },
                        onRetry = { controller.onRetrySession?.invoke() },
                        onConfirm = { edited -> controller.onConfirmAction?.invoke(edited) },
                        onCancelConfirm = { controller.onCancelAction?.invoke() },
                        onPickContact = { chosen -> controller.onPickContact?.invoke(chosen) }
                    )
                }
            }
        }

        runCatching {
            windowManager.addView(view, params)
            overlayView = view
            lifecycleOwner = owner
            layoutParams = params
        }.onFailure {
            owner.detach()
        }
    }

    fun hide() {
        val v = overlayView
        overlayView = null
        if (v != null) {
            runCatching { windowManager.removeViewImmediate(v) }
        }
        lifecycleOwner?.detach()
        lifecycleOwner = null
        layoutParams = null
    }
}

@Composable
private fun VoiceOverlayCardContent(
    state: AssistantRuntimeState,
    onClose: () -> Unit,
    onStopUiAgent: () -> Unit,
    onOpenApp: () -> Unit,
    onRetry: () -> Unit,
    onConfirm: (String?) -> Unit,
    onCancelConfirm: () -> Unit,
    onPickContact: (ContactMatch?) -> Unit
) {
    val orbPhase = when (state.sessionPhase) {
        BackgroundSessionPhase.LISTENING -> VoiceModePhase.LISTENING
        BackgroundSessionPhase.THINKING,
        BackgroundSessionPhase.WAITING_CONFIRMATION -> VoiceModePhase.THINKING
        BackgroundSessionPhase.SPEAKING -> VoiceModePhase.SPEAKING
        BackgroundSessionPhase.ERROR -> VoiceModePhase.ERROR
        BackgroundSessionPhase.IDLE -> VoiceModePhase.IDLE
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // টপ রো: অ্যানিমেটেড মিনি অর্ব, স্ট্যাটাস ও বন্ধ/অ্যাপ বাটন
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VoiceVisualizerOrb(
                    phase = orbPhase,
                    rmsLevel = state.rmsNormalized,
                    accentColor = MaterialTheme.colorScheme.primary,
                    secondaryColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val title = when (state.sessionPhase) {
                        BackgroundSessionPhase.LISTENING -> stringResource(R.string.voice_state_listening)
                        BackgroundSessionPhase.THINKING -> stringResource(R.string.voice_state_thinking)
                        BackgroundSessionPhase.SPEAKING -> stringResource(R.string.voice_state_speaking)
                        BackgroundSessionPhase.WAITING_CONFIRMATION -> stringResource(R.string.action_status_waiting_confirm)
                        BackgroundSessionPhase.ERROR -> stringResource(R.string.voice_state_error)
                        BackgroundSessionPhase.IDLE -> stringResource(R.string.voice_mode_title)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    state.autoCloseSecondsRemaining?.let { sec ->
                        Text(
                            text = stringResource(R.string.overlay_auto_close_hint, sec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onOpenApp) {
                    Text(stringResource(R.string.overlay_open_in_app))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.voice_close))
                }
            }

            // চলমান UI অটোমেশন থামানোর ব্যানার
            if (state.isUiAutomationRunning) {
                UiAgentStatusPill(
                    label = state.uiAutomationLabel,
                    onStopClick = onStopUiAgent
                )
            }

            // চলমান অ্যাকশন কার্ড
            if (state.actionCards.isNotEmpty()) {
                ActionCardsBanner(cards = state.actionCards)
            }

            // ইন-ওভারলে কন্ট্যাক্ট পিকার (যদি একাধিক কন্ট্যাক্ট মেলে)
            state.pendingContactSelection?.let { contactPending ->
                OverlayContactPickerSection(
                    pending = contactPending,
                    onSelect = { onPickContact(it) },
                    onCancel = { onPickContact(null) }
                )
            }

            // ইন-ওভারলে HIGH রিস্ক কনফার্মেশন কার্ড
            state.pendingConfirmation?.let { confirmPending ->
                OverlayConfirmationSection(
                    pending = confirmPending,
                    onConfirm = onConfirm,
                    onCancel = onCancelConfirm
                )
            }

            // লাইভ ট্রান্সক্রিপ্ট / উত্তর / ত্রুটি
            if (state.userTranscript.isNotBlank()) {
                Text(
                    text = "${stringResource(R.string.voice_you_said)} ${state.userTranscript}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.aiReplyPreview.isNotBlank()) {
                Text(
                    text = state.aiReplyPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
            state.errorMessage?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // শেষ হলে বা এরর হলে পুনরায় বলার বাটন
            if (state.sessionPhase == BackgroundSessionPhase.IDLE || state.sessionPhase == BackgroundSessionPhase.ERROR) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(onClick = onRetry) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.voice_tap_to_speak))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayConfirmationSection(
    pending: PendingActionConfirmation,
    onConfirm: (String?) -> Unit,
    onCancel: () -> Unit
) {
    var editingSms by remember(pending.id) { mutableStateOf(false) }
    var smsDraft by remember(pending.id) { mutableStateOf(pending.smsMessage) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(pending.bengaliTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            if (pending.targetAppName.isNotBlank()) {
                Text("${stringResource(R.string.confirm_app_label)} ${pending.targetAppName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            if (pending.targetButtonLabel.isNotBlank()) {
                Text(
                    "${stringResource(R.string.confirm_button_label)} ${pending.targetButtonLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (pending.actionContextDetails.isNotBlank()) {
                Text("${stringResource(R.string.confirm_context_label)} ${pending.actionContextDetails}", style = MaterialTheme.typography.labelSmall)
            }
            if (pending.recipientName.isNotBlank()) {
                Text("${stringResource(R.string.confirm_recipient_label)} ${pending.recipientName}", style = MaterialTheme.typography.bodySmall)
            }
            if (pending.phoneNumber.isNotBlank()) {
                Text("${stringResource(R.string.confirm_number_label)} ${pending.phoneNumber}", style = MaterialTheme.typography.bodySmall)
            }
            if (pending.isSms) {
                if (editingSms) {
                    OutlinedTextField(
                        value = smsDraft,
                        onValueChange = { smsDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                } else {
                    Text(smsDraft, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
            if (pending.isVoiceListeningForConfirm) {
                Text(
                    text = stringResource(R.string.voice_confirm_listening),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(R.string.confirm_timeout_hint, pending.remainingSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                if (pending.isSms && !editingSms) {
                    TextButton(onClick = { editingSms = true }) {
                        Text(stringResource(R.string.confirm_edit_button))
                    }
                }
                Button(onClick = { onConfirm(if (pending.isSms) smsDraft else null) }) {
                    Text(stringResource(R.string.confirm_action_button))
                }
            }
        }
    }
}

@Composable
private fun OverlayContactPickerSection(
    pending: PendingContactSelection,
    onSelect: (ContactMatch) -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(stringResource(R.string.contact_picker_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            pending.matches.take(5).forEach { contact ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(contact) }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(contact.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(contact.phoneNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
