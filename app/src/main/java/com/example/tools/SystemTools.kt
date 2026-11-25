package com.example.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * অ্যালার্ম, টাইমার, ক্যালেন্ডার, অ্যাপ খোলা, টর্চ, ভলিউম, ব্রাইটনেস,
 * Wi-Fi/Bluetooth সেটিংস, ডিভাইস স্ট্যাটাস ও মিউজিক চালানোর টুল এক্সিকিউটর।
 */
object SystemTools {

    @Volatile
    private var lastTorchState: Boolean = false

    fun setAlarm(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        val label = args.optString("label", "").trim()
        val daysArr = args.optJSONArray("days")

        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult(false, "অ্যালার্মের সঠিক ঘণ্টা (0-23) ও মিনিট (0-59) দিন।")
        }

        val daysList = ArrayList<Int>()
        if (daysArr != null) {
            for (i in 0 until daysArr.length()) {
                val d = parseDayOfWeek(daysArr.opt(i))
                if (d != null) daysList.add(d)
            }
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (daysList.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, daysList)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            ctx.appContext.startActivity(intent)
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            ToolResult(true, "$timeStr-এ অ্যালার্ম সেট করা হয়েছে${if (label.isNotBlank()) " ($label)" else ""}।")
        } catch (e: Exception) {
            ToolResult(false, "অ্যালার্ম অ্যাপ পাওয়া যায়নি বা সেট করা যায়নি: ${e.localizedMessage ?: ""}")
        }
    }

    private fun parseDayOfWeek(raw: Any?): Int? {
        return when (raw) {
            is Number -> raw.toInt().takeIf { it in 1..7 }
            is String -> when (raw.trim().lowercase()) {
                "sun", "sunday", "রবি", "রবিবার" -> Calendar.SUNDAY
                "mon", "monday", "সোম", "সোমবার" -> Calendar.MONDAY
                "tue", "tuesday", "মঙ্গল", "মঙ্গলবার" -> Calendar.TUESDAY
                "wed", "wednesday", "বুধ", "বুধবার" -> Calendar.WEDNESDAY
                "thu", "thursday", "বৃহস্পতি", "বৃহস্পতিবার" -> Calendar.THURSDAY
                "fri", "friday", "শুক্র", "শুক্রবার" -> Calendar.FRIDAY
                "sat", "saturday", "শনি", "শনিবার" -> Calendar.SATURDAY
                else -> null
            }
            else -> null
        }
    }

    fun setTimer(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val seconds = args.optInt("seconds", -1)
        val label = args.optString("label", "").trim()

        if (seconds <= 0) {
            return ToolResult(false, "টাইমারের জন্য ১ সেকেন্ড বা তার বেশি সময় দিন।")
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            ctx.appContext.startActivity(intent)
            ToolResult(true, "$seconds সেকেন্ডের টাইমার চালু করা হয়েছে।")
        } catch (e: Exception) {
            ToolResult(false, "টাইমার সেট করা যায়নি: ${e.localizedMessage ?: ""}")
        }
    }

    fun createCalendarEvent(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val title = args.optString("title", "").trim()
        val description = args.optString("description", "").trim()
        val location = args.optString("location", "").trim()
        val startTimeIso = args.optString("start_time", "").trim()
        val durationMinutes = args.optInt("duration_minutes", 60).coerceAtLeast(15)

        if (title.isBlank()) {
            return ToolResult(false, "ক্যালেন্ডার ইভেন্টের শিরোনাম (title) দেওয়া আবশ্যক।")
        }

        val beginMillis = parseIsoOrEpochMillis(startTimeIso) ?: (System.currentTimeMillis() + 3600_000L)
        val endMillis = beginMillis + durationMinutes * 60_000L

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            if (description.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, description)
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            ctx.appContext.startActivity(intent)
            ToolResult(true, "'$title' ইভেন্ট তৈরি করার জন্য ক্যালেন্ডার খোলা হয়েছে।")
        } catch (e: Exception) {
            ToolResult(false, "ডিভাইসে ক্যালেন্ডার অ্যাপ পাওয়া যায়নি।")
        }
    }

    private fun parseIsoOrEpochMillis(raw: String): Long? {
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { return it }
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.parse(raw)?.let { return it.time }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun openApp(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val rawName = args.optString("app_name", "").trim()
        if (rawName.isBlank()) {
            return ToolResult(false, "কোন অ্যাপ খুলতে চান তার নাম বলুন।")
        }

        val pm = ctx.appContext.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherApps = pm.queryIntentActivities(mainIntent, 0)
        if (launcherApps.isEmpty()) {
            return ToolResult(false, "ইনস্টল করা অ্যাপের তালিকা পড়া যায়নি।")
        }

        val query = resolveAppAlias(rawName.lowercase())
        val matched0 = launcherApps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            label == query || label.contains(query) || query.contains(label) || pkg.contains(query)
        }

        // Typo-tolerant fallback (JARVIS-derived similarity): only when nothing matched above, and only a clear winner.
        val matched = matched0 ?: launcherApps
            .map { info -> info to com.example.automation.contacts.ContactRanking.similarity(query, info.loadLabel(pm).toString().lowercase()) }
            .filter { it.second >= 0.80f }
            .sortedByDescending { it.second }
            .let { ranked -> if (ranked.size == 1 || (ranked.size > 1 && ranked[0].second - ranked[1].second >= 0.10f)) ranked.first().first else null }

        if (matched == null) {
            return ToolResult(false, "'$rawName' নামের কোনো অ্যাপ ডিভাইসে খুঁজে পাওয়া যায়নি।")
        }

        val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        } ?: return ToolResult(false, "'${matched.loadLabel(pm)}' অ্যাপটি চালু করা যায়নি।")

        return try {
            ctx.appContext.startActivity(launchIntent)
            ToolResult(true, "${matched.loadLabel(pm)} খোলা হয়েছে।")
        } catch (e: Exception) {
            ToolResult(false, "অ্যাপ খুলতে সমস্যা হয়েছে: ${e.localizedMessage ?: ""}")
        }
    }

    private fun resolveAppAlias(input: String): String {
        val aliases = mapOf(
            "ফেসবুক" to "facebook", "fb" to "facebook",
            "ইউটিউব" to "youtube", "yt" to "youtube",
            "হোয়াটসঅ্যাপ" to "whatsapp", "হোয়াটসঅ্যাপ" to "whatsapp", "wa" to "whatsapp",
            "মেসেঞ্জার" to "messenger",
            "ইনস্টাগ্রাম" to "instagram", "insta" to "instagram",
            "ক্রোম" to "chrome",
            "জিমেইল" to "gmail",
            "ক্যামেরা" to "camera",
            "ক্যালকুলেটর" to "calculator",
            "গ্যালারি" to "photos", "ফটো" to "photos",
            "সেটিংস" to "settings",
            "ম্যাপ" to "maps", "ম্যাপস" to "maps",
            "বিকাশ" to "bkash", "নগদ" to "nagad", "রকেট" to "rocket",
            "স্পটিফাই" to "spotify", "টেলিগ্রাম" to "telegram"
        )
        return aliases[input] ?: input
    }

    fun toggleFlashlight(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val cameraManager = ctx.appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult(false, "ক্যামেরা/টর্চ সার্ভিস পাওয়া যায়নি।")

        return try {
            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facingBack = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlash && facingBack
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }

            if (flashCameraId == null) {
                return ToolResult(false, "এই ডিভাইসে ফ্ল্যাশলাইট (টর্চ) নেই।")
            }

            val targetState = if (args.has("enabled")) args.optBoolean("enabled") else !lastTorchState
            cameraManager.setTorchMode(flashCameraId, targetState)
            lastTorchState = targetState
            val stateLabel = if (targetState) "চালু" else "বন্ধ"
            ToolResult(true, "টর্চলাইট $stateLabel করা হয়েছে।", JSONObject().put("enabled", targetState))
        } catch (e: Exception) {
            ToolResult(false, "টর্চলাইট নিয়ন্ত্রণ করা যায়নি: ${e.localizedMessage ?: ""}")
        }
    }

    fun setVolume(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val audioManager = ctx.appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolResult(false, "অডিও সার্ভিস পাওয়া যায়নি।")

        val streamName = args.optString("stream", "media").lowercase()
        val action = args.optString("action", "set").lowercase()
        val levelPercent = args.optInt("level_percent", -1)

        val streamType = when (streamName) {
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }

        val maxVol = audioManager.getStreamMaxVolume(streamType)
        val curVol = audioManager.getStreamVolume(streamType)

        val targetVol = when (action) {
            "up", "increase" -> (curVol + (maxVol / 5).coerceAtLeast(1)).coerceAtMost(maxVol)
            "down", "decrease" -> (curVol - (maxVol / 5).coerceAtLeast(1)).coerceAtLeast(0)
            "mute" -> 0
            "unmute" -> (maxVol / 2).coerceAtLeast(1)
            else -> {
                if (levelPercent !in 0..100) {
                    return ToolResult(false, "ভলিউমের সঠিক মান (০ থেকে ১০০%) দিন।")
                }
                ((levelPercent / 100f) * maxVol).toInt().coerceIn(0, maxVol)
            }
        }

        return try {
            audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
            val actualPct = if (maxVol > 0) (targetVol * 100) / maxVol else 0
            ToolResult(true, "$streamName ভলিউম $actualPct%-এ সেট করা হয়েছে।")
        } catch (e: Exception) {
            ToolResult(false, "ভলিউম পরিবর্তন করা যায়নি (সম্ভবত Do Not Disturb চালু আছে)।")
        }
    }

    fun setBrightness(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        if (!Settings.System.canWrite(ctx.appContext)) {
            return ToolResult(false, "ব্রাইটনেস পরিবর্তনের জন্য 'Modify system settings' অনুমতি দেওয়া হয়নি।")
        }
        val levelPercent = args.optInt("level_percent", -1)
        if (levelPercent !in 0..100) {
            return ToolResult(false, "ব্রাইটনেসের সঠিক মান (০ থেকে ১০০%) দিন।")
        }

        return try {
            val resolver = ctx.appContext.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val value255 = ((levelPercent / 100f) * 255f).toInt().coerceIn(1, 255)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value255)
            ToolResult(true, "স্ক্রিন ব্রাইটনেস $levelPercent%-এ সেট করা হয়েছে।")
        } catch (e: Exception) {
            ToolResult(false, "ব্রাইটনেস পরিবর্তন করা যায়নি: ${e.localizedMessage ?: ""}")
        }
    }

    fun openWifiBluetoothSettings(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val target = args.optString("target", "wifi").lowercase()
        val intent = when (target) {
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "internet", "mobile_data" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                } else {
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                }
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
            }
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            ctx.appContext.startActivity(intent)
            val label = if (target == "bluetooth") "Bluetooth" else "Wi-Fi / ইন্টারনেট"
            ToolResult(
                success = true,
                message = "$label সেটিংস প্যানেল খুলেছি। Android-এর নিরাপত্তা সীমাবদ্ধতার কারণে অ্যাপ সরাসরি এটি চালু/বন্ধ করতে পারে না, অনুগ্রহ করে আপনি চালু বা বন্ধ করুন।"
            )
        } catch (e: Exception) {
            ToolResult(false, "সেটিংস স্ক্রিন খোলা যায়নি: ${e.localizedMessage ?: ""}")
        }
    }

    fun getDeviceStatus(ctx: ToolExecutionContext): ToolResult {
        val context = ctx.appContext

        // ব্যাটারি তথ্য
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val statusInt = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusInt == BatteryManager.BATTERY_STATUS_FULL

        // নেটওয়ার্ক তথ্য
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkType = if (cm != null) {
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            when {
                caps == null -> "সংযোগ নেই (Offline)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "মোবাইল ডেটা (Cellular)"
                else -> "অন্যান্য নেটওয়ার্ক"
            }
        } else {
            "অজানা"
        }

        // সময় ও তারিখ
        val now = Date()
        val timeFmt = SimpleDateFormat("hh:mm a, EEEE, dd MMM yyyy", Locale.getDefault()).format(now)

        val data = JSONObject().apply {
            put("battery_percent", batteryPct)
            put("is_charging", isCharging)
            put("network", networkType)
            put("current_time", timeFmt)
        }
        val chargingText = if (isCharging) "চার্জ হচ্ছে" else "চার্জ হচ্ছে না"
        return ToolResult(
            success = true,
            message = "ব্যাটারি: $batteryPct% ($chargingText), নেটওয়ার্ক: $networkType, সময়: $timeFmt",
            data = data
        )
    }

    fun playMusic(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val query = args.optString("query", "").trim()
        if (query.isBlank()) {
            return ToolResult(false, "কোন গান বা আর্টিস্ট চালাতে চান তা লিখুন।")
        }

        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pm = ctx.appContext.packageManager
        if (intent.resolveActivity(pm) == null) {
            return ToolResult(false, "গান চালানোর জন্য কোনো সমর্থিত মিউজিক অ্যাপ ডিভাইসে পাওয়া যায়নি।")
        }

        return try {
            ctx.appContext.startActivity(intent)
            ToolResult(true, "'$query' চালানোর জন্য মিউজিক অ্যাপ খোলা হয়েছে।")
        } catch (e: Exception) {
            ToolResult(false, "মিউজিক চালানো যায়নি: ${e.localizedMessage ?: ""}")
        }
    }
}
