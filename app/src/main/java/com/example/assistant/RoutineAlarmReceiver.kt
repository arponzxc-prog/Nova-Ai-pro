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
import com.example.data.local.AppDatabase
import com.example.data.local.RoutineEntity
import com.example.model.ActionCardItem
import com.example.model.ChatMessage
import com.example.model.InputMode
import com.example.network.AiProviderFactory
import com.example.network.ErrorFormatter
import com.example.network.FailoverCandidateResolver
import com.example.network.ModelHealthTracker
import com.example.tools.AgentOrchestrator
import com.example.tools.AgentUiBridge
import com.example.tools.ConfirmationOutcome
import com.example.tools.ConfirmationRequestContext
import com.example.tools.ContactMatch
import com.example.tools.RiskLevel
import com.example.tools.ToolDefinition
import com.example.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * শিডিউলড রুটিন অ্যালার্ম রিসিভার ও ব্যাকগ্রাউন্ড এক্সিকিউটর।
 * নিরাপত্তা নীতি: আনঅ্যাটেনডেড ব্যাকগ্রাউন্ড রুটিনে কেবল RiskLevel.LOW টুলই অনুমোদিত;
 * HIGH এবং MEDIUM ঝুঁকির যেকোনো টুল কোড-লেভেলে কঠোরভাবে ব্লক করা হয়।
 */
class RoutineAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val routineId = intent.getStringExtra(RoutineScheduler.EXTRA_ROUTINE_ID) ?: return
        val pendingResult = goAsync()
        val app = context.applicationContext as? AiAssistantApplication
        if (app == null) {
            pendingResult.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).chatDao()
                val routine = dao.getRoutineById(routineId) ?: return@launch
                if (!routine.enabled) return@launch

                val output = RoutineExecutor.executeRoutine(context, app, routine)
                dao.updateRoutineAfterRun(
                    id = routine.id,
                    lastRunAt = System.currentTimeMillis(),
                    enabled = !routine.oneShot
                )
                if (!routine.oneShot) {
                    RoutineScheduler.schedule(context, routine)
                }
                RoutineExecutor.postRoutineNotification(context, routine.id, routine.title, output)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

object RoutineExecutor {

    const val ROUTINE_CHANNEL_ID = "nexa_routine_channel"

    /**
     * রুটিন চালাতে কেবল RiskLevel.LOW টুল ফিল্টার করে নেয়।
     */
    fun filterAllowedToolsForRoutine(disabledToolNames: Set<String>): List<ToolDefinition> {
        return ToolRegistry.getEnabledTools(disabledToolNames)
            .filter { it.riskLevel == RiskLevel.LOW }
    }

    suspend fun executeRoutine(
        context: Context,
        app: AiAssistantApplication,
        routine: RoutineEntity
    ): String {
        val repo = app.repository
        val models = repo.modelConfigsFlow.first()
        val activeModel = models.find { it.isActive } ?: models.firstOrNull()
        val autoFailover = repo.autoFailoverEnabledFlow.first()
        val orderIds = repo.failoverOrderIdsFlow.first()
        val providerFactory = AiProviderFactory()

        val candidates = FailoverCandidateResolver.buildCandidates(
            activeModel = activeModel,
            allModels = models,
            failoverOrderIds = orderIds,
            autoFailoverEnabled = autoFailover,
            requiresVision = false,
            keyResolver = { repo.getApiKey(it) },
            healthTracker = ModelHealthTracker.shared,
            providerFactory = providerFactory
        )

        val primary = candidates.firstOrNull()
            ?: return routine.prompt // API Key না থাকলে অন্তত রিমাইন্ডার টেক্সট দেখাবে

        val disabledTools = repo.disabledToolsFlow.first()
        val lowRiskTools = filterAllowedToolsForRoutine(disabledTools)
        val systemPrompt = repo.systemPromptFlow.first()

        val bridge = object : AgentUiBridge {
            override suspend fun requestPermissions(
                permissions: List<String>,
                requiresWriteSettings: Boolean,
                explanationRes: Int
            ): Boolean = false

            override suspend fun pickContact(query: String, matches: List<ContactMatch>): ContactMatch? = null

            override suspend fun requestConfirmation(
                tool: ToolDefinition,
                args: JSONObject,
                isVoiceMode: Boolean,
                confirmContext: ConfirmationRequestContext
            ): ConfirmationOutcome {
                // নিরাপত্তার জন্য: ব্যাকগ্রাউন্ড রুটিনে HIGH বা MEDIUM কোনো টুলই চলতে পারবে না
                return if (tool.riskLevel == RiskLevel.LOW && confirmContext.effectiveRiskLevel == RiskLevel.LOW) {
                    ConfirmationOutcome.Confirmed()
                } else {
                    ConfirmationOutcome.Cancelled("ব্যাকগ্রাউন্ড রুটিনে কেবল LOW-ঝুঁকির কাজ অনুমোদিত")
                }
            }

            override fun onActionCardChanged(card: ActionCardItem) {}

            override suspend fun onRecordActionLog(toolName: String, argsSummary: String, status: String) {
                repo.recordActionLog(toolName, "[Routine] $argsSummary", status)
            }
        }

        val orchestrator = AgentOrchestrator(context, bridge)
        return try {
            val result = orchestrator.runAgentLoop(
                provider = primary.provider,
                config = primary.config,
                apiKey = primary.apiKey,
                baseSystemPrompt = systemPrompt,
                history = listOf(ChatMessage(role = "user", content = routine.prompt)),
                enabledTools = lowRiskTools,
                inputMode = InputMode.TEXT,
                logSensitiveData = false,
                preferredSimSlot = -1
            )
            result.finalReply
        } catch (e: Exception) {
            ErrorFormatter.format(context, e)
        }
    }

    fun postRoutineNotification(context: Context, routineId: String, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ROUTINE_CHANNEL_ID,
                context.getString(R.string.notif_channel_boot_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            context,
            routineId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, ROUTINE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("NOVA AI রুটিন: $title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(routineId.hashCode(), notif)
    }
}
