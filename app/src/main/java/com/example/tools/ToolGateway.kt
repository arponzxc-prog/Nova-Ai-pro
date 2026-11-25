/* © ARPON — original NEXA AI code. */
package com.example.tools

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.R
import com.example.automation.core.AutomationSwitches
import com.example.model.ActionCardItem
import com.example.model.ActionCardStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Tools whose target person is resolved from the contact list when only a name is given. */
internal val CONTACT_RESOLVED_TOOLS = setOf(
    "make_call", "send_sms", "open_whatsapp_chat", "send_whatsapp_message", "whatsapp_call", "whatsapp_delete_message"
)

private val UI_RECIPE_TOOLS = setOf(
    "send_whatsapp_message", "whatsapp_call", "whatsapp_delete_message", "youtube_play", "youtube_subscribe", "youtube_fullscreen"
)

/**
 * Runs ONE tool call through the same safety pipeline the text/voice agent loop uses, for callers that are not the
 * agent loop (Gemini Live tool calls, the rule-based CommandRouter):
 *   disabled-tools setting -> automation kill switch / feature toggle -> argument validation -> contact resolution
 *   (+ picker) -> just-in-time permissions -> lock-screen block for HIGH risk -> user confirmation -> execution.
 * The kill switch / feature toggle is additionally enforced inside the executor itself (AutomationSwitches.guard),
 * so no path can skip it. Secure-screen blocking stays inside the accessibility tools (ScreenSecurityGuard).
 */
class ToolGateway(
    private val appContext: Context,
    private val bridge: AgentUiBridge,
    /** Same function the agent loop uses (AgentOrchestrator.resolveConfirmationContext). */
    private val confirmationContext: (ToolDefinition, JSONObject) -> ConfirmationRequestContext,
    private val executionContext: suspend () -> ToolExecutionContext,
    private val disabledTools: suspend () -> Set<String>,
    /** Drives the existing UiAgentIndicatorOverlay (via AssistantRuntimeController) while a UI-driving tool runs. */
    private val onUiAutomationRunning: (running: Boolean, label: String) -> Unit = { _, _ -> }
) {
    suspend fun execute(toolName: String, rawArgs: JSONObject, isVoice: Boolean): ToolResult {
        val tool = ToolRegistry.getTool(toolName) ?: return ToolResult(false, "অজানা টুল: $toolName")
        if (toolName in disabledTools()) return ToolResult(false, "'${tool.bengaliTitle}' টুলটি সেটিংসে বন্ধ করা আছে।")
        AutomationSwitches.blockReason(toolName, AutomationSwitches.settings)?.let { return ToolResult(false, it) }

        val args = JSONObject(rawArgs.toString()) // never mutate the caller's object
        ToolRegistry.validateArguments(tool, args)?.let {
            bridge.onRecordActionLog(toolName, it, "INVALID_ARGS")
            return ToolResult(false, "$it — অনুমান না করে ইউজারকে জিজ্ঞেস করুন।")
        }

        val cardId = UUID.randomUUID().toString()
        var summary = readableSummary(toolName, args)
        suspend fun card(status: ActionCardStatus, detail: String? = null) = bridge.onActionCardChanged(
            ActionCardItem(id = cardId, toolName = toolName, displayTitle = tool.bengaliTitle, summary = summary, status = status, detailMessage = detail.orEmpty())
        )
        card(ActionCardStatus.RUNNING)

        // 1. contact resolution
        if (toolName in CONTACT_RESOLVED_TOOLS && args.optString("phone_number", "").isBlank()) {
            val query = args.optString("contact_name", "").trim()
            val allowed = hasPerms(listOf(Manifest.permission.READ_CONTACTS)) ||
                bridge.requestPermissions(listOf(Manifest.permission.READ_CONTACTS), false, R.string.perm_desc_read_contacts)
            if (!allowed) return fail(cardId, tool, summary, "কন্ট্যাক্টস পড়ার অনুমতি দেওয়া হয়নি।", "PERMISSION_DENIED")
            val matches = withContext(Dispatchers.IO) { ContactAndPhoneHelper.searchContacts(appContext, query) }
            val chosen = when {
                matches.isEmpty() -> return fail(cardId, tool, summary, "'$query' নামে কোনো কন্ট্যাক্ট পাওয়া যায়নি।", "CONTACT_NOT_FOUND")
                matches.size == 1 -> matches.first()
                else -> {
                    card(ActionCardStatus.WAITING_CONFIRMATION)
                    bridge.pickContact(query, matches) ?: run {
                        card(ActionCardStatus.CANCELLED, "কন্ট্যাক্ট নির্বাচন বাতিল")
                        bridge.onRecordActionLog(toolName, summary, "CANCELLED")
                        return ToolResult(false, "ইউজার কন্ট্যাক্ট নির্বাচন বাতিল করেছেন।")
                    }
                }
            }
            args.put("phone_number", chosen.phoneNumber).put("contact_name", chosen.name)
            summary = readableSummary(toolName, args)
        }

        // 2. just-in-time permissions
        val needsRuntime = !hasPerms(tool.requiredPermissions)
        val needsWrite = tool.requiresWriteSettings && !Settings.System.canWrite(appContext)
        if (needsRuntime || needsWrite) {
            val granted = bridge.requestPermissions(tool.requiredPermissions, tool.requiresWriteSettings, permissionExplanation(tool))
            if (!granted) return fail(cardId, tool, summary, "প্রয়োজনীয় অনুমতি না দেওয়ায় '${tool.bengaliTitle}' চালানো যায়নি।", "PERMISSION_DENIED")
        }

        // 3. lock screen + confirmation (HIGH risk is always confirmed in code, never trusted to the model)
        val ctx = confirmationContext(tool, args)
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true && ctx.effectiveRiskLevel == RiskLevel.HIGH) {
            return fail(cardId, tool, summary, appContext.getString(R.string.lockscreen_unlock_required), "LOCKED_BLOCKED")
        }
        val outcome = bridge.requestConfirmation(tool, args, isVoice, ctx)
        if (outcome is ConfirmationOutcome.Cancelled) {
            card(ActionCardStatus.CANCELLED, outcome.reason)
            bridge.onRecordActionLog(toolName, summary, "CANCELLED")
            return ToolResult(false, "ইউজার অ্যাকশনটি বাতিল করেছেন (${outcome.reason})।")
        } else if (outcome is ConfirmationOutcome.Confirmed && outcome.editedSmsMessage != null) {
            args.put("message", outcome.editedSmsMessage)
        }

        // kill switch may have been flipped while the user was reading the confirmation
        AutomationSwitches.blockReason(toolName, AutomationSwitches.settings)?.let {
            return fail(cardId, tool, summary, it, "KILL_SWITCH")
        }

        // 4. execute (the existing indicator overlay shows UI-driving tools; its stop button cancels the task)
        val drivesUi = toolName in UI_RECIPE_TOOLS ||
            AutomationSwitches.TOOL_FEATURE[toolName] == com.example.automation.core.AutomationFeature.SCREEN_AGENT
        if (drivesUi) onUiAutomationRunning(true, tool.bengaliTitle)
        val result = try {
            withContext(Dispatchers.IO) {
                runCatching { tool.executor(executionContext(), args) }
                    .getOrElse { ToolResult(false, "টুল চালাতে ত্রুটি হয়েছে (${it.javaClass.simpleName})।") }
            }
        } finally {
            if (drivesUi) onUiAutomationRunning(false, "")
        }
        card(if (result.success) ActionCardStatus.SUCCESS else ActionCardStatus.FAILED, result.message)
        bridge.onRecordActionLog(toolName, summary, if (result.success) "SUCCESS" else "FAILED")
        return result
    }

    private suspend fun fail(cardId: String, tool: ToolDefinition, summary: String, msg: String, status: String): ToolResult {
        bridge.onActionCardChanged(
            ActionCardItem(id = cardId, toolName = tool.name, displayTitle = tool.bengaliTitle, summary = summary, status = ActionCardStatus.FAILED, detailMessage = msg)
        )
        bridge.onRecordActionLog(tool.name, summary, status)
        return ToolResult(false, msg)
    }

    private fun hasPerms(perms: List<String>) =
        perms.all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }

    private fun permissionExplanation(tool: ToolDefinition): Int = when {
        Manifest.permission.CALL_PHONE in tool.requiredPermissions -> R.string.perm_desc_call_phone
        Manifest.permission.READ_CONTACTS in tool.requiredPermissions -> R.string.perm_desc_read_contacts
        Manifest.permission.ACCESS_COARSE_LOCATION in tool.requiredPermissions -> R.string.perm_desc_location
        tool.requiresWriteSettings -> R.string.perm_desc_write_settings
        else -> R.string.perm_desc_read_contacts
    }

    /** Short, non-sensitive description for the action card/log (message bodies are never included). */
    internal fun readableSummary(toolName: String, args: JSONObject): String {
        val name = args.optString("contact_name", "")
        val num = args.optString("phone_number", "")
        val who = if (name.isNotBlank() && num.isNotBlank()) "$name ($num)" else name.ifBlank { num }
        return when (toolName) {
            "make_call", "open_whatsapp_chat", "send_sms", "send_whatsapp_message", "whatsapp_call" -> who
            "whatsapp_delete_message" -> who + if (args.optBoolean("for_everyone", false)) " — সবার জন্য" else " — শুধু আমার জন্য"
            "open_url" -> args.optString("url", args.optString("query", ""))
            "youtube_play", "youtube_search" -> args.optString("query", "")
            "media_control" -> args.optString("action", "")
            else -> ""
        }
    }
}
