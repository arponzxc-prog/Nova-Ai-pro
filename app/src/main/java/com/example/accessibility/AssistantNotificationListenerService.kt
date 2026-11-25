package com.example.accessibility

import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.AiAssistantApplication
import com.example.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * চাহিদামতো সাম্প্রতিক নোটিফিকেশন পড়ার জন্য NotificationListenerService।
 * - ডিস্কে কিছুই সেভ করে না, শুধু NotificationMemoryStore (RAM)-এ রাখে।
 * - OTP/ব্যাংকিং নোটিফিকেশন ডিফল্টে বাদ রাখে।
 * - ঐচ্ছিক অটো-স্পিক ডিফল্টে বন্ধ থাকে।
 */
class AssistantNotificationListenerService : NotificationListenerService() {

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ttsManager: TtsManager? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
    }

    override fun onListenerDisconnected() {
        _isConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return

        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val body = bigText.ifBlank { normalText }

        if (title.isBlank() && body.isBlank()) return

        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))

        val captured = CapturedNotification(
            key = sbn.key ?: "${pkg}_${sbn.id}",
            packageName = pkg,
            appLabel = appLabel,
            title = title,
            text = body,
            timestampMs = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        )

        val accepted = NotificationMemoryStore.addNotification(captured)
        if (!accepted) return

        scope.launch {
            val app = applicationContext as? AiAssistantApplication ?: return@launch
            val autoRead = app.repository.autoReadNotificationsFlow.first()
            if (!autoRead) return@launch

            val allowedPkgs = app.repository.notifAllowedPackagesFlow.first()
            if (allowedPkgs.isNotEmpty() && allowedPkgs.none { it.equals(pkg, ignoreCase = true) }) {
                return@launch
            }

            // কল বা ভয়েস সেশন চললে বিরক্ত করবে না
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val inCall = audioManager?.mode == AudioManager.MODE_IN_CALL ||
                audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION
            if (inCall || app.assistantController.state.value.isSessionActive) return@launch

            val summary = "${captured.appLabel} থেকে নোটিফিকেশন: ${captured.title}. ${captured.text}".take(180)
            val tts = ttsManager ?: TtsManager(applicationContext).also { ttsManager = it }
            tts.speak(null, summary)
        }
    }

    override fun onDestroy() {
        _isConnected.value = false
        ttsManager?.release()
        ttsManager = null
        scope.cancel()
        super.onDestroy()
    }
}
