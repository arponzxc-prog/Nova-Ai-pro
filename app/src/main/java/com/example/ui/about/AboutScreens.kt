/* © ARPON — original NOVA AI code. Honest info screens: name, version, license, credits. No identity "verification". */
package com.example.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.hud.GlassCard
import com.example.ui.hud.LiquidGlowBackground
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateBack: () -> Unit, onOpenNotices: () -> Unit) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.about_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            )
        }
    ) { padding ->
        LiquidGlowBackground {
            Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).testTag("about_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCard {
                    Text(AboutContent.APP_AND_DEVELOPER, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (version.isNotBlank()) Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium)
                }
                GlassCard {
                    Text(stringResource(R.string.about_credits_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(AboutContent.JARVIS_CREDIT, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("about_credit"))
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutContent.JARVIS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text(stringResource(R.string.about_open_link)) }
                }
                Button(onClick = onOpenNotices, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("about_notices_button")) {
                    Text(stringResource(R.string.about_notices_button))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState(initialValue = "") {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            listOf(AboutContent.NOTICES_ASSET, AboutContent.LICENSE_ASSET).joinToString("\n\n————————————————\n\n") { asset ->
                runCatching { context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.notices_title)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.about_back)) } }
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).testTag("notices_screen")) {
            Text(AboutContent.PROJECT_MAINTAINER, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(AboutContent.JARVIS_CREDIT, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
