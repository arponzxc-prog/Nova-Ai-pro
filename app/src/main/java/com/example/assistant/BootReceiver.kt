package com.example.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.AiAssistantApplication
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ডিভাইস রিস্টার্ট বা অ্যাপ আপডেটের পর রিমাইন্ডার ব্রডকাস্ট রিসিভার।
 * Android 14+ (API 34+) নীতিমালায় BOOT_COMPLETED থেকে সরাসরি microphone Foreground Service
 * শুরু করা নিষিদ্ধ (SecurityException হয়)। তাই সৎভাবে "অ্যাসিস্ট্যান্ট চালু করতে ট্যাপ করুন" নোটিফিকেশন দেখায়।
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val BOOT_CHANNEL_ID = "assistant_boot_channel"
        const val BOOT_NOTIF_ID = 4002
        const val EXTRA_START_BG_SERVICE = "extra_start_bg_service"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        val app = context.applicationContext as? AiAssistantApplication
        if (app == null) {
            pendingResult.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // শিডিউলড রুটিন ও রিমাইন্ডার পুনরায় সেট করা
                runCatching { RoutineScheduler.rescheduleAllEnabled(context) }

                val bgWasEnabled = app.repository.backgroundListeningEnabledFlow.first()
                val reminderEnabled = app.repository.bootNotificationEnabledFlow.first()
                if (!bgWasEnabled || !reminderEnabled) return@launch

                // Android 13+ এ POST_NOTIFICATIONS অনুমতি না থাকলে নোটিফিকেশন পোস্ট করা যাবে না
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasPostNotif = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasPostNotif) return@launch
                }

                showTapToStartNotification(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showTapToStartNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BOOT_CHANNEL_ID,
                context.getString(R.string.notif_channel_boot_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_START_BG_SERVICE, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            403,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.boot_notif_title))
            .setContentText(context.getString(R.string.boot_notif_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.boot_notif_body)))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(BOOT_NOTIF_ID, notif)
    }
}
