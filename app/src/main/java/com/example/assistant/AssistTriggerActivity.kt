package com.example.assistant

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.example.AiAssistantApplication
import com.example.MainActivity

/**
 * ডিফল্ট ডিজিটাল অ্যাসিস্ট্যান্ট (ACTION_ASSIST / VOICE_COMMAND) হিসেবে নির্বাচিত হলে
 * হোম বাটন লং-প্রেস বা অ্যাসিস্ট জেসচারে অ্যাপটির ভয়েস সেশন চালু করে।
 */
class AssistTriggerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as AiAssistantApplication
        val controller = app.assistantController

        if (controller.state.value.isServiceRunning && Settings.canDrawOverlays(this)) {
            startService(
                Intent(this, AssistantService::class.java)
                    .setAction(AssistantService.ACTION_TRIGGER_VOICE)
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(AssistantService.EXTRA_OPEN_VOICE_MODE, true)
                }
            )
        }
        finish()
    }
}
