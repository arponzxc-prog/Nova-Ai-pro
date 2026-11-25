package com.example.assistant

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.AiAssistantApplication
import com.example.MainActivity
import com.example.R

/**
 * Quick Settings প্যানেল থেকে এক ট্যাপে ভয়েস সেশন চালু করার টাইল সার্ভিস।
 * - ব্যাকগ্রাউন্ড সার্ভিস ও Overlay পারমিশন থাকলে সরাসরি ওভারলে ভয়েস সেশন খোলে।
 * - নইলে Android 14+ PendingIntent নিয়ম মেনে VoiceModeScreen সহ অ্যাপ খোলে।
 */
class AssistantTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val app = application as AiAssistantApplication
        val running = app.assistantController.state.value.isServiceRunning
        qsTile?.apply {
            label = getString(R.string.qs_tile_label)
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val app = application as AiAssistantApplication
        val controller = app.assistantController

        val action: () -> Unit = {
            if (controller.state.value.isServiceRunning && Settings.canDrawOverlays(this)) {
                val svcIntent = Intent(this, AssistantService::class.java)
                    .setAction(AssistantService.ACTION_TRIGGER_VOICE)
                runCatching { startService(svcIntent) }
            } else {
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(AssistantService.EXTRA_OPEN_VOICE_MODE, true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = PendingIntent.getActivity(
                        this,
                        402,
                        activityIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(activityIntent)
                }
            }
        }

        if (isLocked) {
            unlockAndRun(action)
        } else {
            action()
        }
    }
}
