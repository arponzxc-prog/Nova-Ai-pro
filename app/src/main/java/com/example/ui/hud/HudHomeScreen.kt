/* © ARPON — original NOVA AI code. HUD home: orb driven by AssistantRuntimeController, dock navigation. */
package com.example.ui.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.AiAssistantApplication
import com.example.R
import com.example.ui.theme.LocalNovaTokens

@Composable
fun HudHomeScreen(onOpenChat: () -> Unit, onOpenSettings: () -> Unit, onMic: () -> Unit) {
    val controller = (LocalContext.current.applicationContext as AiAssistantApplication).assistantController
    val runtime by controller.state.collectAsStateWithLifecycle()
    val orb = OrbStateMapper.from(runtime)
    val t = LocalNovaTokens.current
    val orbLabel = stringResource(
        when (orb) {
            OrbState.IDLE -> R.string.hud_state_idle
            OrbState.LISTENING -> R.string.hud_state_listening
            OrbState.THINKING -> R.string.hud_state_thinking
            OrbState.SPEAKING -> R.string.hud_state_speaking
        }
    )
    val tabs = remember {
        listOf(
            DockTab("home", "Home", Icons.Filled.Home), DockTab("chat", "Chat", Icons.Filled.Chat), DockTab("settings", "Settings", Icons.Filled.Settings)
        )
    }
    val homeLabel = stringResource(R.string.dock_home); val chatLabel = stringResource(R.string.dock_chat); val setLabel = stringResource(R.string.dock_settings)
    val localTabs = remember(homeLabel, chatLabel, setLabel) { listOf(tabs[0].copy(label = homeLabel), tabs[1].copy(label = chatLabel), tabs[2].copy(label = setLabel)) }

    LiquidGlowBackground(Modifier.testTag("hud_home")) {
        Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = t.textPrimary, modifier = Modifier.padding(top = 24.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                NovaOrb(state = orb, level = runtime.rmsNormalized, modifier = Modifier.fillMaxSize(), description = orbLabel)
            }
            Text(
                if (runtime.liveStatus.isNotEmpty()) runtime.liveStatus else orbLabel,
                style = MaterialTheme.typography.titleMedium, color = t.textSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)
            )
            NovaDock(
                tabs = localTabs, selectedId = "home", micDescription = stringResource(R.string.dock_mic),
                onTab = { id -> if (id == "chat") onOpenChat() else if (id == "settings") onOpenSettings() },
                onMic = onMic
            )
        }
    }
}
