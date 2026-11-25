package com.example.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.AppDatabase
import com.example.data.local.RoutineEntity
import com.example.tools.ToolExecutionContext
import com.example.tools.ToolResult
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * AlarmManager-ভিত্তিক রুটিন ও রিমাইন্ডার শিডিউলার:
 * - Doze মোডেও চলতে setExactAndAllowWhileIdle (অনুমতি থাকলে) অথবা setAndAllowWhileIdle ব্যবহার করে
 * - Android 12+ (API 31+) এ canScheduleExactAlarms() যাচাই করে SecurityException এড়ায়
 */
object RoutineScheduler {

    const val EXTRA_ROUTINE_ID = "extra_routine_id"

    fun computeNextTriggerMillis(
        hour: Int,
        minute: Int,
        daysMask: String,
        oneShot: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val allowedDays: Set<Int>? = if (daysMask.equals("DAILY", ignoreCase = true) || daysMask.isBlank() || oneShot) {
            null
        } else {
            daysMask.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in Calendar.SUNDAY..Calendar.SATURDAY }
                .toSet()
                .takeIf { it.isNotEmpty() }
        }

        val candidate = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (candidate.timeInMillis <= nowMs + 1000L) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }

        if (allowedDays != null) {
            var guard = 0
            while (candidate.get(Calendar.DAY_OF_WEEK) !in allowedDays && guard < 8) {
                candidate.add(Calendar.DAY_OF_YEAR, 1)
                guard++
            }
        }
        return candidate.timeInMillis
    }

    fun schedule(context: Context, routine: RoutineEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildPendingIntent(context, routine.id)
        if (!routine.enabled) {
            alarmManager.cancel(pi)
            return
        }

        val triggerAt = computeNextTriggerMillis(
            hour = routine.hour,
            minute = routine.minute,
            daysMask = routine.daysMask,
            oneShot = routine.oneShot
        )

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context, routineId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildPendingIntent(context, routineId)
        alarmManager.cancel(pi)
    }

    suspend fun rescheduleAllEnabled(context: Context) {
        val routines = AppDatabase.getInstance(context).chatDao().getEnabledRoutines()
        routines.forEach { schedule(context, it) }
    }

    private fun buildPendingIntent(context: Context, routineId: String): PendingIntent {
        val intent = Intent(context, RoutineAlarmReceiver::class.java).apply {
            action = "com.example.ACTION_RUN_ROUTINE"
            putExtra(EXTRA_ROUTINE_ID, routineId)
        }
        return PendingIntent.getBroadcast(
            context,
            routineId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    suspend fun scheduleRoutineFromTool(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val title = args.optString("title", "").trim()
        val prompt = args.optString("prompt", "").trim()
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        val oneShot = args.optBoolean("one_shot", false)
        val daysMask = args.optString("days_mask", "DAILY").ifBlank { "DAILY" }

        if (title.isBlank() || prompt.isBlank() || hour !in 0..23 || minute !in 0..59) {
            return ToolResult(false, "সঠিক title, prompt, hour (0-23) এবং minute (0-59) উল্লেখ করুন।")
        }

        val routine = RoutineEntity(
            id = UUID.randomUUID().toString(),
            title = title.take(80),
            prompt = prompt.take(500),
            hour = hour,
            minute = minute,
            daysMask = daysMask,
            enabled = true,
            oneShot = oneShot,
            createdAt = System.currentTimeMillis()
        )
        AppDatabase.getInstance(ctx.appContext).chatDao().insertRoutine(routine)
        schedule(ctx.appContext, routine)

        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        return ToolResult(
            success = true,
            message = "রুটিন সেট করা হয়েছে: \"$title\" ($timeStr)। নির্ধারিত সময়ে শুধু নিরাপদ (LOW-ঝুঁকির) কাজ চালিয়ে নোটিফিকেশন দেখাবে।",
            data = JSONObject().apply {
                put("routine_id", routine.id)
                put("title", routine.title)
                put("time", timeStr)
            }
        )
    }
}
