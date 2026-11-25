package com.example

import android.app.Application
import com.example.assistant.AssistantRuntimeController
import com.example.camerapill.CameraPillController
import com.example.data.AiRepository
import com.example.automation.agent.SerializedActionExecutor
import com.example.automation.core.AutomationSelectors
import com.example.automation.core.AutomationSwitches
import com.example.voice.owner.OwnerVoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.example.voice.owner.VoskModelManager

/**
 * সহজ Manual Dependency Injection-এর জন্য Application ক্লাস।
 * অ্যাপ জুড়ে একই Repository ও AssistantRuntimeController ইনস্ট্যান্স সরবরাহ করে।
 */
class AiAssistantApplication : Application() {
    val repository: AiRepository by lazy { AiRepository(this) }
    val assistantController: AssistantRuntimeController by lazy { AssistantRuntimeController(this) }
    val cameraPillController: CameraPillController by lazy {
        CameraPillController(this, repository, assistantController)
    }
    // Voice engine (Gemini Live / Owner Voice). Lazy: nothing is loaded until the feature is used.
    val voskModelManager: VoskModelManager by lazy { VoskModelManager(this) }
    val ownerVoiceManager: OwnerVoiceManager by lazy { OwnerVoiceManager(voskModelManager, repository.secretStore) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Automation kill switch: mirror the persisted setting into the synchronous guard, and stop everything
        // that is running the moment it is switched on.
        AutomationSwitches.addKillListener {
            SerializedActionExecutor.cancelAll()
            assistantController.requestStopUiAgent(false)
        }
        appScope.launch {
            AutomationSelectors.loadOverrides(this@AiAssistantApplication)
            repository.automationSettingsFlow.collect { AutomationSwitches.update(it) }
        }
    }
}
