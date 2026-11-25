package com.example.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

enum class OemBrand(val displayName: String) {
    XIAOMI("Xiaomi / Redmi / POCO (MIUI / HyperOS)"),
    OPPO_REALME_ONEPLUS("Oppo / Realme / OnePlus (ColorOS)"),
    VIVO("Vivo / iQOO (FuntouchOS)"),
    SAMSUNG("Samsung (One UI)"),
    STOCK_OTHER("Android (Stock / Pixel / Other)")
}

/**
 * ডিভাইস নির্মাতা শনাক্তকরণ, ব্যাটারি অপ্টিমাইজেশন, চার্জিং অবস্থা এবং রাতের সময়সূচি হেল্পার।
 */
object BatteryOemHelper {

    fun detectOemBrand(manufacturer: String = Build.MANUFACTURER.orEmpty()): OemBrand {
        val m = manufacturer.trim().lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> OemBrand.XIAOMI
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> OemBrand.OPPO_REALME_ONEPLUS
            m.contains("vivo") || m.contains("iqoo") -> OemBrand.VIVO
            m.contains("samsung") -> OemBrand.SAMSUNG
            else -> OemBrand.STOCK_OTHER
        }
    }

    fun getOemBatterySteps(brand: OemBrand): List<String> {
        return when (brand) {
            OemBrand.XIAOMI -> listOf(
                "১. Settings → Apps → Manage apps → AI Assistant → Autostart চালু করুন।",
                "২. একই স্ক্রিনে Battery saver-এ গিয়ে \"No restrictions\" বেছে নিন।",
                "৩. Recents স্ক্রিনে অ্যাপ কার্ডটি লং-প্রেস করে Lock (তালা) আইকনে ট্যাপ করুন।"
            )
            OemBrand.OPPO_REALME_ONEPLUS -> listOf(
                "১. Settings → App management → AI Assistant → Battery usage → \"Allow background activity\" চালু করুন।",
                "২. \"Allow auto-launch\" (স্বয়ংক্রিয় চালু) অপশনটি অন করুন।",
                "৩. Recents-এ অ্যাপ কার্ডের ৩-ডট মেনু থেকে Lock করুন।"
            )
            OemBrand.VIVO -> listOf(
                "১. Settings → Battery → Background power consumption management → AI Assistant → \"High background power usage\" বেছে নিন।",
                "২. iManager → App management → Autostart-এ অ্যাপটি অন করুন।"
            )
            OemBrand.SAMSUNG -> listOf(
                "১. Settings → Apps → AI Assistant → Battery → \"Unrestricted\" নির্বাচন করুন।",
                "২. Settings → Battery & device care → Battery → Background usage limits → \"Never sleeping apps\"-এ AI Assistant যোগ করুন।"
            )
            OemBrand.STOCK_OTHER -> listOf(
                "১. উপরের \"ব্যাটারি অপ্টিমাইজেশন বন্ধ করুন\" বাটনে ট্যাপ করে Allow দিন।",
                "২. App Info → App battery usage → \"Unrestricted\" বেছে নিন।"
            )
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun createIgnoreBatteryOptimizationIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openOemBatteryOrAppSettings(context: Context) {
        val brand = detectOemBrand()
        val candidateIntents = buildList {
            when (brand) {
                OemBrand.XIAOMI -> {
                    add(
                        Intent().setComponent(
                            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                        )
                    )
                }
                OemBrand.OPPO_REALME_ONEPLUS -> {
                    add(
                        Intent().setComponent(
                            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                        )
                    )
                    add(
                        Intent().setComponent(
                            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")
                        )
                    )
                }
                OemBrand.VIVO -> {
                    add(
                        Intent().setComponent(
                            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                        )
                    )
                }
                OemBrand.SAMSUNG -> {
                    add(Intent("com.samsung.android.sm.ACTION_BATTERY"))
                }
                OemBrand.STOCK_OTHER -> Unit
            }
            add(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }

        for (intent in candidateIntents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return
        }
    }

    fun isDeviceCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
    }

    /**
     * রাতের সময়সূচি হিসাব করে (যেমন ২৩:০০ থেকে ০৭:০০ পর্যন্ত মধ্যরাত অতিক্রম করলেও সঠিকভাবে ধরবে)।
     */
    fun isWithinQuietHours(currentHour: Int, startHour: Int, endHour: Int): Boolean {
        val h = currentHour.coerceIn(0, 23)
        val s = startHour.coerceIn(0, 23)
        val e = endHour.coerceIn(0, 23)
        if (s == e) return false
        return if (s < e) {
            h in s until e
        } else {
            h >= s || h < e
        }
    }
}
