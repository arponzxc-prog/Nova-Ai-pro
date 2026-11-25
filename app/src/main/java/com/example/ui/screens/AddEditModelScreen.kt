package com.example.ui.screens

import android.os.Build
import android.view.View
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.ModelConfig
import com.example.model.ProviderType
import com.example.security.SecureKeyStore
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

data class ProviderPreset(
    val name: String,
    val providerType: ProviderType,
    val defaultBaseUrl: String,
    val defaultModelName: String,
    val description: String
)

private val providerPresets = listOf(
    ProviderPreset("OpenRouter", ProviderType.OPENAI, "https://openrouter.ai/api/v1", "anthropic/claude-3.5-sonnet", "OpenRouter API (OpenAI, Anthropic, Gemini, etc.)"),
    ProviderPreset("APIQIK", ProviderType.OPENAI, "https://apiqik.com/v1", "gpt-4o", "APIQIK High-Speed AI API Gateway"),
    ProviderPreset("WusRouter", ProviderType.OPENAI, "https://wusrouter.com/v1", "gpt-4o", "WusRouter Free & Fast AI Gateway"),
    ProviderPreset("Xingchi", ProviderType.OPENAI, "https://api.xingchi.ai/v1", "gpt-4o", "Xingchi AI Provider"),
    ProviderPreset("Ollama", ProviderType.OPENAI, "http://10.0.2.2:11434/v1", "llama3", "Local Ollama AI"),
    ProviderPreset("LM Studio", ProviderType.OPENAI, "http://10.0.2.2:1234/v1", "local-model", "Local LM Studio")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditModelScreen(
    viewModel: MainViewModel,
    modelId: String?,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val models by viewModel.modelConfigs.collectAsStateWithLifecycle()

    // সবসময় modelId ধরে খোঁজা হয়, কখনো তালিকার index ধরে নয়
    val existingModel = remember(modelId, models) {
        if (modelId == null) null else models.find { it.id == modelId }
    }

    // স্ক্রিনে Android Autofill ও পাসওয়ার্ড ম্যানেজার সাজেশন সম্পূর্ণ বন্ধ রাখা
    DisposableEffect(hostView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val prevImportance = hostView.importantForAutofill
            hostView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            runCatching {
                context.getSystemService(AutofillManager::class.java)?.cancel()
            }
            onDispose {
                hostView.importantForAutofill = prevImportance
            }
        } else {
            onDispose {}
        }
    }

    // নতুন যোগ করার সময় প্রতিবার সম্পূর্ণ খালি অবস্থা থেকে শুরু (modelId-র ওপর keyed)
    var hasLoadedExisting by remember(modelId) { mutableStateOf(false) }
    var displayName by remember(modelId) { mutableStateOf("") }
    var selectedProvider by remember(modelId) { mutableStateOf(ProviderType.OPENAI) }
    var baseUrl by remember(modelId) { mutableStateOf("") }
    var modelName by remember(modelId) { mutableStateOf("") }
    var temperature by remember(modelId) { mutableFloatStateOf(0.7f) }
    var supportsTools by remember(modelId) { mutableStateOf(true) }
    var supportsVision by remember(modelId) { mutableStateOf(false) }
    var supportsStreaming by remember(modelId) { mutableStateOf(true) }

    // কেবল ওই নির্দিষ্ট configId-র নিজস্ব Key স্টেট
    var storedKey by remember(modelId) { mutableStateOf("") }
    var apiKeyInput by remember(modelId) { mutableStateOf("") }
    var isKeyEdited by remember(modelId) { mutableStateOf(modelId == null) }

    var showProviderDropdown by remember(modelId) { mutableStateOf(false) }
    var showApiKeyVisibility by remember(modelId) { mutableStateOf(false) }
    var isTestingConnection by remember(modelId) { mutableStateOf(false) }

    var nameError by remember(modelId) { mutableStateOf(false) }
    var modelNameError by remember(modelId) { mutableStateOf(false) }

    // Edit মোডে মডেলের ফিল্ডগুলো এক-বার লোড করা (async StateFlow দেরিতে এলেও সঠিক ডেটা পাবে)
    LaunchedEffect(modelId, existingModel) {
        if (modelId != null && existingModel != null && !hasLoadedExisting) {
            displayName = existingModel.name
            selectedProvider = existingModel.providerType
            baseUrl = existingModel.baseUrl
            modelName = existingModel.modelName
            temperature = existingModel.temperature
            supportsTools = existingModel.supportsTools
            supportsVision = existingModel.supportsVision
            supportsStreaming = existingModel.supportsStreaming
            hasLoadedExisting = true
        }
    }

    // Edit মোডে অফ-মেইন থ্রেডে কেবল ওই configId-র নিজস্ব Key ডিক্রিপ্ট করে লোড করা
    LaunchedEffect(modelId) {
        if (modelId != null) {
            val decrypted = viewModel.getApiKeyAsync(modelId)
            storedKey = decrypted
            if (!isKeyEdited) {
                isKeyEdited = decrypted.isBlank()
            }
        } else {
            storedKey = ""
            apiKeyInput = ""
            isKeyEdited = true
        }
    }

    val maskedSavedKey = remember(storedKey) { SecureKeyStore.maskKey(storedKey) }
    val displayKeyFieldText = if (!isKeyEdited && storedKey.isNotBlank()) {
        if (showApiKeyVisibility) storedKey else maskedSavedKey
    } else {
        apiKeyInput
    }
    val keyVisualTransformation = if (!isKeyEdited && storedKey.isNotBlank()) {
        VisualTransformation.None
    } else if (showApiKeyVisibility) {
        VisualTransformation.None
    } else {
        PasswordVisualTransformation()
    }

    val suggestedModelHint = when (selectedProvider) {
        ProviderType.OPENAI -> "gpt-4o"
        ProviderType.ANTHROPIC -> "claude-3-5-sonnet"
        ProviderType.GEMINI -> "gemini-2.5-flash"
    }

    var selectedTab by remember(modelId) { mutableStateOf(0) } // 0 = Quick Presets, 1 = Custom Provider

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(if (modelId == null) stringResource(R.string.add_model) else stringResource(R.string.edit_model)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (modelId == null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text(stringResource(R.string.tab_quick_presets)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text(stringResource(R.string.tab_custom_provider)) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (modelId == null && selectedTab == 0) {
                Text(
                    text = stringResource(R.string.select_provider_preset),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                providerPresets.forEach { preset ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                displayName = preset.name
                                selectedProvider = preset.providerType
                                baseUrl = preset.defaultBaseUrl
                                modelName = preset.defaultModelName
                                selectedTab = 1
                            }
                    ) {
                        ListItem(
                            headlineContent = { Text(preset.name, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text(preset.description, style = MaterialTheme.typography.bodySmall) },
                            trailingContent = {
                                FilledTonalButton(onClick = {
                                    displayName = preset.name
                                    selectedProvider = preset.providerType
                                    baseUrl = preset.defaultBaseUrl
                                    modelName = preset.defaultModelName
                                    selectedTab = 1
                                }) {
                                    Text(stringResource(R.string.configure_preset))
                                }
                            }
                        )
                    }
                }
            } else {
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    nameError = false
                },
                label = { Text(stringResource(R.string.display_name)) },
                isError = nameError,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("model_display_name_input"),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next)
            )
            if (nameError) {
                Text(stringResource(R.string.error_required_field), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            ExposedDropdownMenuBox(
                expanded = showProviderDropdown,
                onExpandedChange = { showProviderDropdown = it }
            ) {
                OutlinedTextField(
                    value = selectedProvider.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.provider_type)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderDropdown) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = showProviderDropdown,
                    onDismissRequest = { showProviderDropdown = false }
                ) {
                    ProviderType.entries.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            onClick = {
                                val prevDefault = selectedProvider.defaultBaseUrl
                                selectedProvider = provider
                                // আগে অন্য প্রোভাইডারের ডিফল্ট URL বসানো থাকলে কেবল তখনই নতুন ডিফল্টে বদলাবে
                                if (baseUrl == prevDefault) {
                                    baseUrl = provider.defaultBaseUrl
                                }
                                showProviderDropdown = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.base_url)) },
                placeholder = { Text(selectedProvider.defaultBaseUrl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("model_base_url_input"),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next
                )
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = displayKeyFieldText,
                    onValueChange = { newText ->
                        if (!isKeyEdited && storedKey.isNotBlank()) {
                            // সংরক্ষিত মাস্কড অংশের ভেতরে এডিট করলে পরিষ্কারভাবে নতুন Key নেওয়া হবে
                            isKeyEdited = true
                            apiKeyInput = when {
                                !showApiKeyVisibility && maskedSavedKey.isNotEmpty() && newText.startsWith(maskedSavedKey) ->
                                    newText.removePrefix(maskedSavedKey)
                                !showApiKeyVisibility && maskedSavedKey.isNotEmpty() && newText.length < maskedSavedKey.length ->
                                    ""
                                else -> newText
                            }
                        } else {
                            isKeyEdited = true
                            apiKeyInput = newText
                        }
                    },
                    label = { Text(stringResource(R.string.api_key)) },
                    placeholder = { Text("sk-... / AIza...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_api_key_input"),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    visualTransformation = keyVisualTransformation,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next
                    ),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (displayKeyFieldText.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        isKeyEdited = true
                                        apiKeyInput = ""
                                    },
                                    modifier = Modifier.testTag("clear_api_key_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Clear,
                                        contentDescription = stringResource(R.string.clear_api_key)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showApiKeyVisibility = !showApiKeyVisibility },
                                modifier = Modifier.testTag("toggle_api_key_visibility")
                            ) {
                                Icon(
                                    imageVector = if (showApiKeyVisibility) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = "API Key Visibility"
                                )
                            }
                        }
                    }
                )
                if (modelId != null && storedKey.isNotBlank() && !isKeyEdited) {
                    Text(
                        text = stringResource(R.string.saved_key_masked_hint, maskedSavedKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            OutlinedTextField(
                value = modelName,
                onValueChange = {
                    modelName = it
                    modelNameError = false
                },
                label = { Text(stringResource(R.string.model_name)) },
                placeholder = { Text(suggestedModelHint) },
                isError = modelNameError,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("model_name_input"),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                )
            )
            if (modelNameError) {
                Text(stringResource(R.string.error_required_field), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // টুল সাপোর্ট টগল (ডিফল্ট অন)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.supports_tools_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.supports_tools_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = supportsTools,
                        onCheckedChange = { supportsTools = it }
                    )
                }
            }

            // ভিশন / ছবি সাপোর্ট টগল (স্ক্রিনশট AI বিশ্লেষণের জন্য)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.supports_vision_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.supports_vision_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = supportsVision,
                        onCheckedChange = { supportsVision = it }
                    )
                }
            }

            // স্ট্রিমিং সাপোর্ট টগল (ডিফল্ট অন)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.supports_streaming_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.supports_streaming_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = supportsStreaming,
                        onCheckedChange = { supportsStreaming = it }
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.temperature), fontWeight = FontWeight.Bold)
                        Text(String.format(Locale.getDefault(), "%.2f", temperature))
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        steps = 19
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val effectiveKey = if (isKeyEdited) apiKeyInput.trim() else storedKey
                    if (effectiveKey.isBlank()) {
                        Toast.makeText(context, "আগে API Key দিন", Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    isTestingConnection = true
                    coroutineScope.launch {
                        try {
                            val tempConfig = ModelConfig(
                                id = modelId ?: "test",
                                name = displayName.trim(),
                                providerType = selectedProvider,
                                baseUrl = baseUrl.trim().ifBlank { selectedProvider.defaultBaseUrl },
                                modelName = modelName.trim().ifBlank { suggestedModelHint },
                                temperature = temperature,
                                supportsTools = supportsTools,
                                supportsVision = supportsVision,
                                supportsStreaming = supportsStreaming
                            )
                            val success = viewModel.testConnection(tempConfig, effectiveKey)
                            if (success) {
                                Toast.makeText(context, context.getString(R.string.connection_success), Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            val safeMsg = SecureKeyStore.redactSecrets(e.localizedMessage ?: "")
                            Toast.makeText(context, "${context.getString(R.string.connection_failed)}$safeMsg", Toast.LENGTH_LONG).show()
                        } finally {
                            isTestingConnection = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test_connection_button"),
                shape = RoundedCornerShape(12.dp),
                enabled = !isTestingConnection
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("টেস্ট করা হচ্ছে...")
                } else {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.test_connection))
                }
            }

            Button(
                onClick = {
                    nameError = displayName.isBlank()
                    modelNameError = modelName.isBlank()

                    if (!nameError && !modelNameError) {
                        val id = modelId ?: UUID.randomUUID().toString()
                        val config = ModelConfig(
                            id = id,
                            name = displayName.trim(),
                            providerType = selectedProvider,
                            baseUrl = baseUrl.trim().ifBlank { selectedProvider.defaultBaseUrl },
                            modelName = modelName.trim(),
                            temperature = temperature,
                            isActive = existingModel?.isActive ?: models.isEmpty(),
                            supportsTools = supportsTools,
                            supportsVision = supportsVision,
                            supportsStreaming = supportsStreaming
                        )
                        val finalKey = if (isKeyEdited) apiKeyInput.trim() else storedKey
                        viewModel.saveModelConfig(
                            config = config,
                            apiKey = finalKey,
                            keyModified = isKeyEdited
                        )
                        onNavigateBack()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("save_model_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.save))
            }
          }
        }
    }
}
