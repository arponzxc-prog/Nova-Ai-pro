/* © ARPON — original NEXA AI code. Converts NEXA ToolDefinitions to Gemini Live function declarations. */
package com.example.voice.live

import com.example.automation.core.AutomationSwitches
import com.example.tools.ToolDefinition
import org.json.JSONObject

object LiveToolDeclarations {

    /** Non-automation tools that are safe and useful in a voice conversation. Memory/routine/search tools stay out. */
    private val EXTRA_ALLOWED = setOf("get_current_time", "set_alarm", "set_timer", "get_device_status", "search_contacts")

    /** Tools exposed to Live: enabled automation tools (feature toggle on, kill switch off) plus [EXTRA_ALLOWED]. */
    fun select(enabled: List<ToolDefinition>): List<ToolDefinition> {
        val s = AutomationSwitches.settings
        if (s.killSwitch) return enabled.filter { it.name in EXTRA_ALLOWED }
        return enabled.filter { t ->
            if (AutomationSwitches.isAutomationTool(t.name)) AutomationSwitches.blockReason(t.name, s) == null else t.name in EXTRA_ALLOWED
        }
    }

    /** Gemini rejects an OBJECT schema with no properties, so parameterless tools omit "parameters". */
    fun declare(tools: List<ToolDefinition>): List<JSONObject> = tools.map { t ->
        JSONObject().put("name", t.name).put("description", t.description).also {
            if (t.parametersSchema.properties.isNotEmpty()) it.put("parameters", t.parametersSchema.toJsonSchema(uppercaseTypes = true))
        }
    }
}
