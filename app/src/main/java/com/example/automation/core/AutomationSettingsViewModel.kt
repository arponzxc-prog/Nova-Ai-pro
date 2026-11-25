/* © ARPON — original NEXA AI code. */
package com.example.automation.core

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AiAssistantApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AutomationSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as AiAssistantApplication).repository

    val settings: StateFlow<AutomationSettings> =
        repo.automationSettingsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationSettings())

    private val _youtubeKeyMasked = MutableStateFlow("")
    val youtubeKeyMasked: StateFlow<String> = _youtubeKeyMasked.asStateFlow()

    init { viewModelScope.launch { _youtubeKeyMasked.value = repo.getMaskedYoutubeApiKey() } }

    private fun save(t: (AutomationSettings) -> AutomationSettings) {
        viewModelScope.launch { repo.saveAutomationSettings(t(settings.value)) }
    }

    /** Takes effect immediately: AutomationSwitches is updated by the settings collector and its kill listeners fire. */
    fun setKillSwitch(on: Boolean) = save { it.copy(killSwitch = on) }
    fun setFastRouting(on: Boolean) = save { it.copy(fastCommandRouting = on) }
    fun setFeature(f: AutomationFeature, on: Boolean) = save {
        it.copy(enabledFeatures = if (on) it.enabledFeatures + f else it.enabledFeatures - f)
    }

    fun saveYoutubeKey(plain: String) {
        viewModelScope.launch { repo.saveYoutubeApiKey(plain); _youtubeKeyMasked.value = repo.getMaskedYoutubeApiKey() }
    }
}
