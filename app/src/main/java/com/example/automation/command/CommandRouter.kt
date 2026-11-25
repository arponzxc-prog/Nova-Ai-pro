/* © ARPON — original NEXA AI code. */
package com.example.automation.command

import com.example.automation.core.AutomationSwitches
import com.example.tools.ToolGateway
import com.example.tools.ToolResult

data class RoutedResult(val command: ParsedCommand, val result: ToolResult)

/**
 * Optional offline fast path: a clearly phrased command runs without a model round-trip, but ONLY through
 * [ToolGateway] — the same disabled-tools check, kill switch, permissions, lock-screen block and user confirmation
 * as a model-initiated tool call. Opt-in (Settings → Automation → fast command routing), off by default.
 */
class CommandRouter(private val gateway: ToolGateway) {

    /** Returns null when the text is not a clear command or routing is off, so the caller continues with the model. */
    suspend fun tryRoute(text: String, isVoice: Boolean): RoutedResult? {
        val s = AutomationSwitches.settings
        if (!s.fastCommandRouting || s.killSwitch) return null
        val parsed = CommandParser.parse(text) ?: return null
        if (parsed.confidence < CommandParser.CONFIDENCE_THRESHOLD) return null
        return RoutedResult(parsed, gateway.execute(parsed.toolName, parsed.args, isVoice))
    }
}
