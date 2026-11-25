package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.AiAssistantApplication
import com.example.MainActivity
import com.example.R
import com.example.accessibility.UiAgentIndicatorOverlay
import com.example.audio.AudioRouteManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * ব্যাকগ্রাউন্ডে অ্যাসিস্ট্যান্ট চালু রাখার Foreground Service (foregroundServiceType="microphone")।
 * - START_STICKY হওয়ায় সিস্টেম কিল করলে ফিরে আসার চেষ্টা করে।
 * - স্থায়ী নোটিফিকেশনে স্পষ্ট দেখায় মাইক শুনছে নাকি "লিসেনিং বন্ধ আছে"।
 * - অন্য অ্যাপ বা কল মাইক নিলে স্বয়ংক্রিয়ভাবে Wake Word থামিয়ে দেয় এবং পরে আবার চালু করে।
 */
class AssistantService : Service() {

    companion object {
        const val CHANNEL_ID = "assistant_foreground_channel"
        const val NOTIFICATION_ID = 4001

        const val ACTION_START = "com.example.assistant.ACTION_START"
        const val ACTION_TOGGLE_PAUSE = "com.example.assistant.ACTION_TOGGLE_PAUSE"
        const val ACTION_TRIGGER_VOICE = "com.example.assistant.ACTION_TRIGGER_VOICE"
        const val ACTION_STOP_SERVICE = "com.example.assistant.ACTION_STOP_SERVICE"
        const val ACTION_REFRESH_CONFIG = "com.example.assistant.ACTION_REFRESH_CONFIG"

        const val EXTRA_OPEN_VOICE_MODE = "extra_open_voice_mode"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val app by lazy { application as AiAssistantApplication }
    private val repository by lazy { app.repository }
    private val controller by lazy { app.assistantController }

    private var sessionManager: BackgroundVoiceSessionManager? = null
    private var bubbleOverlay: FloatingBubbleOverlay? = null
    private var sessionOverlay: VoiceSessionOverlay? = null
    private var uiIndicatorOverlay: UiAgentIndicatorOverlay? = null

    private var userPaused = false
    private var otherMicOrCallActive = false
    private var currentThemeMode = "system"
    private var scheduleCheckJob: Job? = null

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val myWakeSessionId = controller.wakeWordEngine.state.value.audioSessionId
            val isSessionActive = controller.state.value.isSessionActive
            // আমাদের নিজের Wake Word সেশন বাদে অন্য কোনো অ্যাপ মাইক ব্যবহার করলে বিরতি
            val anotherRecording = !isSessionActive && (configs.orEmpty().any {
                it.clientAudioSessionId != myWakeSessionId
            })
            val inCall = audioManager.mode == AudioManager.MODE_IN_CALL ||
                (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION && !AudioRouteManager.ownsCommunicationMode)

            val conflict = anotherRecording || inCall
            if (conflict != otherMicOrCallActive) {
                otherMicOrCallActive = conflict
                mainHandler.post { evaluateListeningAndUpdateNotification() }
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            evaluateListeningAndUpdateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Camera Pill observes this service's runtime state; start() is idempotent (single overlay instance).
        app.cameraPillController.start()

        sessionManager = BackgroundVoiceSessionManager(
            context = applicationContext,
            repository = repository,
            controller = controller,
            onSessionEnded = {
                sessionOverlay?.hide()
                evaluateListeningAndUpdateNotification()
            }
        )

        bubbleOverlay = FloatingBubbleOverlay(
            context = applicationContext,
            controller = controller,
            onBubbleTapped = { triggerBackgroundVoiceSession() }
        )

        sessionOverlay = VoiceSessionOverlay(
            context = applicationContext,
            controller = controller,
            themeModeProvider = { currentThemeMode },
            onOpenInApp = { convId ->
                sessionManager?.endSession()
                openMainApp(openVoiceMode = false, conversationId = convId)
            }
        )

        uiIndicatorOverlay = UiAgentIndicatorOverlay(
            context = applicationContext,
            controller = controller,
            themeModeProvider = { currentThemeMode }
        )

        audioManager.registerAudioRecordingCallback(recordingCallback, mainHandler)
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Wake Word ইভেন্ট শুনলে সাথে সাথে ব্যাকগ্রাউন্ড ভয়েস সেশন চালু
        scope.launch {
            controller.wakeWordEngine.events.collect {
                if (controller.state.value.isServiceRunning && !controller.state.value.isSessionActive) {
                    triggerBackgroundVoiceSession()
                }
            }
        }

        // সেটিংস বদলালে সার্ভিসের কনফিগ, বাবল ও Wake Word আপডেট করা
        scope.launch {
            repository.themeModeFlow.collect { currentThemeMode = it }
        }
        scope.launch {
            repository.wakeWordPhraseFlow.collect { phrase ->
                val th = repository.wakeWordThresholdFlow.first()
                controller.wakeWordEngine.updateConfig(phrase, th)
                updateNotification()
            }
        }
        scope.launch {
            repository.wakeWordThresholdFlow.collect { th ->
                val phrase = repository.wakeWordPhraseFlow.first()
                controller.wakeWordEngine.updateConfig(phrase, th)
            }
        }
        scope.launch {
            repository.floatingBubbleEnabledFlow.collect { enabled ->
                if (enabled && Settings.canDrawOverlays(this@AssistantService)) {
                    bubbleOverlay?.show()
                } else {
                    bubbleOverlay?.hide()
                }
            }
        }
        scope.launch {
            controller.state.collect { rtState ->
                val stopEnabled = repository.uiFloatingStopEnabledFlow.first()
                val handledByA11y = com.example.accessibility.AssistantAccessibilityService.instance != null
                if (!handledByA11y && rtState.isUiAutomationRunning && stopEnabled && Settings.canDrawOverlays(this@AssistantService)) {
                    uiIndicatorOverlay?.show()
                } else {
                    uiIndicatorOverlay?.hide()
                }
            }
        }

        // প্রতি মিনিটে রাতের সময়সূচি ও কলের অবস্থা যাচাই
        scheduleCheckJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                evaluateListeningAndUpdateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 12+ / 14+ সীমাবদ্ধতার কারণে ফোরগ্রাউন্ড শুরুতে Exception ধরতে হবে যাতে ক্র্যাশ না হয়
        val startedForeground = ensureForegroundNotification()
        if (!startedForeground) {
            controller.setServiceRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        controller.setServiceRunning(true)

        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> {
                userPaused = !userPaused
                evaluateListeningAndUpdateNotification()
            }
            ACTION_TRIGGER_VOICE -> {
                triggerBackgroundVoiceSession()
            }
            ACTION_STOP_SERVICE -> {
                scope.launch { repository.saveBackgroundListeningEnabled(false) }
                shutdownService()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_CONFIG, ACTION_START, null -> {
                evaluateListeningAndUpdateNotification()
            }
        }

        return START_STICKY
    }

    private fun ensureForegroundNotification(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: Exception) {
            // Android 12+/14+ এ ব্যাকগ্রাউন্ড থেকে ফোরগ্রাউন্ড মাইক সার্ভিস শুরু আটকে দিলে নিরাপদে বন্ধ
            false
        }
    }

    private fun triggerBackgroundVoiceSession() {
        if (controller.state.value.isSessionActive) return

        // প্রথমে Wake Word ইঞ্জিন থামিয়ে মাইক সম্পূর্ণ মুক্ত করা
        controller.wakeWordEngine.stop()

        if (Settings.canDrawOverlays(this)) {
            sessionOverlay?.show()
            sessionManager?.startVoiceSession(playChime = true)
        } else {
            // ওভারলে পারমিশন না থাকলে নিরাপদ বিকল্প: অ্যাপের VoiceModeScreen খোলা
            openMainApp(openVoiceMode = true, conversationId = null)
        }
    }

    private fun evaluateListeningAndUpdateNotification() {
        scope.launch {
            val chargingOnly = repository.chargingOnlyWakeFlow.first()
            val nightEnabled = repository.nightScheduleEnabledFlow.first()
            val nightStart = repository.nightStartHourFlow.first()
            val nightEnd = repository.nightEndHourFlow.first()
            val bubbleEnabled = repository.floatingBubbleEnabledFlow.first()

            if (bubbleEnabled && Settings.canDrawOverlays(this@AssistantService)) {
                bubbleOverlay?.show()
            } else {
                bubbleOverlay?.hide()
            }

            val inCallNow = audioManager.mode == AudioManager.MODE_IN_CALL ||
                (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION && !AudioRouteManager.ownsCommunicationMode)
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

            val reason = when {
                userPaused -> PauseReason.USER_PAUSED
                otherMicOrCallActive || inCallNow -> PauseReason.OTHER_MIC_OR_CALL
                chargingOnly && !BatteryOemHelper.isDeviceCharging(this@AssistantService) -> PauseReason.NOT_CHARGING
                nightEnabled && BatteryOemHelper.isWithinQuietHours(currentHour, nightStart, nightEnd) -> PauseReason.NIGHT_SCHEDULE
                else -> PauseReason.NONE
            }

            controller.updatePauseState(userPaused, reason)

            if (controller.state.value.isSessionActive) {
                controller.wakeWordEngine.stop()
            } else if (reason == PauseReason.NONE) {
                val phrase = repository.wakeWordPhraseFlow.first()
                val threshold = repository.wakeWordThresholdFlow.first()
                controller.wakeWordEngine.updateConfig(phrase, threshold)
                controller.wakeWordEngine.start(monitorAudioEvenIfModelMissing = false)
            } else {
                controller.wakeWordEngine.stop()
            }

            updateNotification()
        }
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = controller.state.value
        val wakeState = controller.wakeWordEngine.state.value
        val isPaused = state.isEffectivelyPaused

        val title = when {
            isPaused -> getString(R.string.notif_title_paused)
            wakeState.status == WakeWordStatus.LISTENING || wakeState.status == WakeWordStatus.COOLDOWN ->
                getString(R.string.notif_title_listening, wakeState.activeKeyword)
            else -> getString(R.string.notif_title_ready)
        }

        val text = when (state.effectivePauseReason) {
            PauseReason.USER_PAUSED -> getString(R.string.notif_text_paused_user)
            PauseReason.OTHER_MIC_OR_CALL -> getString(R.string.notif_text_paused_call)
            PauseReason.NOT_CHARGING -> getString(R.string.notif_text_paused_battery)
            PauseReason.NIGHT_SCHEDULE -> getString(R.string.notif_text_paused_schedule)
            PauseReason.NONE -> {
                when (wakeState.status) {
                    WakeWordStatus.MISSING_AAR -> getString(R.string.wake_word_status_missing_aar)
                    WakeWordStatus.MISSING_MODEL -> getString(R.string.wake_word_status_missing_model)
                    else -> getString(R.string.notif_text_listening)
                }
            }
        }

        val pauseIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, AssistantService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speakIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, AssistantService::class.java).setAction(ACTION_TRIGGER_VOICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            103,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            104,
            Intent(this, AssistantService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseLabel = if (userPaused) getString(R.string.notif_action_resume) else getString(R.string.notif_action_pause)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, pauseLabel, pauseIntent)
            .addAction(0, getString(R.string.notif_action_speak), speakIntent)
            .addAction(0, getString(R.string.notif_action_open_app), openAppIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    private fun openMainApp(openVoiceMode: Boolean, conversationId: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_VOICE_MODE, openVoiceMode)
            if (conversationId != null) {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
        }
        runCatching { startActivity(intent) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_assistant_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_assistant_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun shutdownService() {
        controller.wakeWordEngine.stop()
        bubbleOverlay?.hide()
        sessionOverlay?.hide()
        uiIndicatorOverlay?.hide()
        sessionManager?.endSession()
        controller.setServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scheduleCheckJob?.cancel()
        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { unregisterReceiver(powerReceiver) }
        controller.wakeWordEngine.stop()
        bubbleOverlay?.hide()
        sessionOverlay?.hide()
        uiIndicatorOverlay?.hide()
        sessionManager?.release()
        controller.setServiceRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
