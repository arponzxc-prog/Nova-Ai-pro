# PROJECT_HANDOVER.md — NEXA AI

- Package `com.example`, applicationId `com.aistudio.aiassistant.pxqtr`, versionName 1.0 (versionCode 1), minSdk 24, target/compileSdk 36.
- Stack: Kotlin 2.4.21, AGP 9.1.1, Gradle 9.3.1 (gradlew, gradlew.bat and gradle/wrapper/gradle-wrapper.jar ARE present in the ZIP as of the JSON-import milestone — earlier notes saying they were missing were outdated), Compose BOM 2024.09.00, Room+KSP, DataStore, OkHttp/Retrofit/Moshi, Firebase AI.
- Manual DI via `AiAssistantApplication` (repository, assistantController, cameraPillController).

## Latest work: UI + branding (Step 3 of 3, PARTIAL)
**Status: written, NOT compiled, NOT run.**
- Theme: `ui/theme/NovaTheme.kt` (+`Theme.kt` wiring, `AiRepository.novaThemeFlow`). Screens inherit via MaterialTheme; hard-coded `Color(0x…)` in camera pill/other files are untouched.
- Orb: `ui/hud/*`, assets in `assets/nova_hud/`. State comes from `AssistantRuntimeController.state` via `OrbStateMapper`.
- Branding: user-visible strings renamed; identifiers unchanged. To rename applicationId/package later: (1) change `applicationId` and `namespace`, (2) move packages with the IDE refactor, (3) update manifest `android:name` entries and any hard-coded `com.example` action strings (`AssistantService.ACTION_*`), (4) new applicationId = new app for Android: existing data, DataStore, Keystore-encrypted keys, permissions, accessibility/notification-listener grants and the signing-linked Play listing do NOT carry over; plan an export/import (JSON backup exists) and a re-grant flow.
- Not done: see CHANGELOG "NOT DONE in this step".

## Latest work: Automation (Step 2 of 3)
**Status: written, NOT compiled, NOT run.** Run `testDebugUnitTest`, then `docs/AUTOMATION_TEST_CHECKLIST.md`.
- ONE accessibility service (`AssistantAccessibilityService`, +`dispatchLongPress`) and ONE agent loop (`AgentOrchestrator`). `ToolGateway` is not a loop: it executes a single tool call with the same safety steps (Live tool calls, fast router).
- Kill switch: `AutomationSwitches` (DataStore → `AiAssistantApplication` collector). `ToolRegistry.allTools` wraps every tool in `AutomationSwitches.TOOL_FEATURE` with `guard()`; until settings are first loaded the guard waits up to 2 s then refuses.
- Live tools: `LiveToolDeclarations.select()` exposes automation tools whose feature is on + a few safe info tools; Live confirmations are touch-only (manager skips TTS/STT when `live != null`).
- Selectors: `AutomationSelectors` defaults + optional `files/automation_selectors.json` overrides (validated; unknown keys ignored).
- Open items: MediaProjection fallback for API 24-29, Live tool-confirmation voice cue, chat-history persistence of fast-path/Live actions, relative brightness.

## Latest work: Voice engine (Step 1 of 3) — Gemini Live / Owner Voice / Hands-free
**Status: written, NOT compiled, NOT run.** Next step: sync, build, run `testDebugUnitTest`, then the device checklist in `docs/VOICE_ENGINE_TEST_CHECKLIST.md`.
- Mic ownership is unchanged: `AssistantService` stops WakeWordEngine → `BackgroundVoiceSessionManager.startVoiceSession` → either `LiveVoiceCoordinator` (Gemini Live) or the legacy flow. Only one `AudioRecord` exists at a time (`MicCapture` or sherpa wake word or SpeechRecognizer).
- Flow: `VoiceMode` setting (default LEGACY) → `LiveVoiceCoordinator.start()` returns `Started` or `Fallback(reason)`; runtime failures call `onFailed` → manager shuts Live down (awaiting mic release) and starts the legacy flow with a notice in `errorMessage`.
- Camera Pill: coordinator publishes via `controller.updateSessionState` (THINKING while connecting/reconnecting, LISTENING, SPEAKING with playback RMS); `isLiveVoice`/`liveStatus` are additive.
- Owner Voice applies **only to Gemini Live sessions**; the legacy flow has no speaker check. If it is enabled and the profile/models are missing, Live does not start (fail-closed) and legacy runs with a visible notice.
- Gemini key: `AiRepository.saveGeminiLiveKey/getGeminiLiveKey` (SecureKeyStore id `gemini_live_api_key`). Owner profile: SecureKeyStore id `owner_voice_profile` (encrypted).
- No tools are declared for Live (Step 2). A tool call is answered with `tool_not_available`.
- Live conversations are NOT written to NEXA chat history/memory (Step 2/3 decision).
- Licensing: see `LICENSE-JARVIS-SOURCE.md` — owner/license of the JARVIS code is unresolved.

## Architecture (as found)
Compose UI + NavHost in MainActivity; `MainViewModel` (large, ~1.6k lines) owns SpeechInputManager, TtsManager, voice-mode state; `AssistantService` (microphone FGS, START_STICKY) hosts wake word, `BackgroundVoiceSessionManager`, bubble/session/UI-agent overlays; `AssistantRuntimeController` is the shared state hub (`state`: isSessionActive, sessionPhase, rmsNormalized…); AiRepository = DataStore + Room + SecureKeyStore; AI providers with failover in `network/`; tools/accessibility/notification services in `tools/` and `accessibility/`.

## Latest work: Camera Pill (this milestone)
See CHANGELOG.md, FILE_MANIFEST.md, DEVELOPMENT_PROGRESS.md. Data flow:
`SpeechInputManager.rms / TtsManager.isSpeaking / voiceModeState` → MainViewModel publisher → `AssistantRuntimeController.inAppVoiceSignal`;
`BackgroundVoiceSessionManager` → `AssistantRuntimeController.state` →
`CameraPillController` (combines + settings + connectivity + permission) → `CameraPillUiModel` → `CameraPillOverlay` (WindowManager) → `CameraPillContent`.

## Key decisions
- No second mic session: only existing RMS is consumed. TextToSpeech exposes no amplitude → AI wave is procedural, synced to speaking state (honest limitation).
- Overlay owner is the Application-scoped controller (one instance); the existing FGS (`AssistantService`) keeps the process alive for background/Always-On.
- Avatar is vector 2.5D art, not a 3D model.
- INTERRUPTED is inferred (SPEAKING/THINKING → LISTENING); "happy" for 1.4 s after SPEAKING ends.
- Pill is non-touchable/non-focusable.

## Permissions (all pre-existing; none added)
RECORD_AUDIO, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, ACCESS_NETWORK_STATE (+ others for other features).

## Known issues / unverified
- **Nothing from this milestone is compiled or device-tested.**
- Wake-word-only listening is not a pill "LISTENING" state (only an active voice session is).
- "Always On" shows only while the assistant service (background listening) or a voice session is active; if background listening is off, only in-app voice mode triggers the pill (the settings screen warns about this).
- OEM variance: some ROMs clip overlays in the cutout region.
- Pre-existing: README mentions Gemini `.env`; `debug.keystore` IS present at the project root of the ZIP (standard debug key, password `android`); earlier note saying it was missing was outdated.

## Build
Open in Android Studio; ensure `debug.keystore` exists at project root (or adjust debug signingConfig); `.env` optional. Unit tests: `./gradlew testDebugUnitTest`.

## Exact next steps
See NEXT_ACCOUNT_INSTRUCTIONS.md.


## Latest work: Bulk JSON import for Models & API Keys
- Entry point: Models & API Keys screen (`ModelListScreen`) -> "IMPORT FROM JSON" button -> SAF picker -> `MainViewModel.onModelImportFileSelected` -> `ModelImportParser.parse` -> `AiRepository.previewModelImport` -> user confirms (Skip/Update duplicates) -> `AiRepository.applyModelImport` -> summary.
- Supported JSON: array of models, or object with `models` / `configs` / `providers` / `items` array, or a single model object. Field aliases are in `ModelImportParser` (e.g. modelName|modelId|model_id|model; apiKey|api_key|key; baseUrl|base_url|endpoint|url). Provider: openai/anthropic(claude)/gemini(google) get a default baseUrl; any other provider name needs a baseUrl (provider type is inferred from host: anthropic.com -> ANTHROPIC, generativelanguage.googleapis.com -> GEMINI, else OPENAI). Sample: docs/nexa_models_import_sample.json.
- Required per entry: modelName + (provider or baseUrl). apiKey optional (model is imported with "key missing" status; updates without apiKey keep the existing key).
- Atomicity: all valid entries are written in ONE DataStore edit; keys are written to SecureKeyStore first and rolled back (new keys deleted, replaced keys restored) if anything fails. Invalid entries are rejected individually (reported as Failed); a storage failure aborts the whole import with existing config untouched.
- Decisions: active model is never changed by an import; imported models are inactive; same model with a different name AND different key is added as a separate config (supports multiple keys for failover); http:// URLs are accepted because the app already allows local Ollama/LM Studio presets.
- **Not compiled, not unit-tested, not device-tested** (sandbox has no Android SDK/Gradle/kotlinc/network). See DEVELOPMENT_PROGRESS.md.
