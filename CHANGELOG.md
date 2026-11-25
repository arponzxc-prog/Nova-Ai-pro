# CHANGELOG

## Unreleased — UI + branding (Step 3 of 3, PARTIAL)
### Added
- Theme system: `NovaTheme.kt` tokens for dark / light / red / sunset (values from JARVIS, contrast-corrected to WCAG AA), `ThemeSelectionSheet`, DataStore key `nova_theme_choice` (default `auto` = follow the existing light/dark/system setting). `MyApplicationTheme` now maps tokens into the Material colour scheme (opaque surfaces), so every existing screen adopts the look without edits.
- HUD: `assets/nova_hud/orb.html` (+ unchanged `three.min.js`), `NovaOrb` (hardened WebView: local assets only, network blocked, no JS interface, destroyed on dispose, paused when not resumed, 30 fps cap) with Canvas fallback; `OrbStateMapper` maps runtime state incl. Gemini Live states; `HudHomeScreen` with glow background, glass cards and a dock with centre mic (route `hud_home`, entry in Settings).
- About / credits / notices screens (`ui/about`), bundled `assets/legal/*` (kept in sync by a test), honest info only — no identity verification.
- NOVA AI vector launcher icon (adaptive + monochrome) and regenerated legacy PNGs; old NEXA logo JPG deleted.
### Changed (branding)
- User-visible name -> "NOVA AI" in both locales (app name, tile, boot notification, service labels, identity strings, camera-pill descriptions, routine notification title, export titles/file names, OpenRouter `X-Title`), IdentityResponder (still recognises the old name Nexa) + tests, developer string "ARPON".
- Kept on purpose: applicationId `com.aistudio.aiassistant.pxqtr`, package `com.example`, notification channel IDs, backup format tag, wake-word preset names (they are the spoken keyword).
### NOT DONE in this step (see report)
Dock navigation app-wide / Updates tab, chat bubble restyle, overlay avatars + gallery, Privacy Shield / Persona / API-connection screens, explicit restyle of each screen (they inherit the theme only), reduce-motion handling beyond renderer choice.
### Verification
NOT compiled, NOT run. Contrast ratios were computed numerically (Python) for the JARVIS tokens; the test suite asserts the corrected ones.

## Unreleased — Automation (Step 2 of 3): phone control, YouTube/WhatsApp, screen-agent building blocks
### Added
- Global **Automation kill switch**, per-feature toggles (phone, media, web, YouTube, WhatsApp, screen control), opt-in fast command routing, optional YouTube API key (Settings → Automation control). The kill switch is enforced inside every automation tool's executor (`AutomationSwitches.guard` in `ToolRegistry`), fails closed until settings are loaded, and cancels running UI tasks.
- New tools in `ToolRegistry` (same schema/validation/confirmation pipeline as existing tools): `media_control`, `open_url`, `get_weather_location`, `youtube_search`, `youtube_play`, `youtube_subscribe` (HIGH), `youtube_fullscreen`, `whatsapp_call` (HIGH), `whatsapp_delete_message` (HIGH).
- `ToolGateway`: runs a single tool call through disabled-tools check → kill switch → validation → contact resolution/picker → JIT permissions → lock-screen block → confirmation → execution. Used by Gemini Live tool calls (Live now declares enabled tools; confirmation is on-screen/touch only while Live owns the mic) and by `CommandRouter`.
- `CommandParser` + `CommandRouter` (rule-based phrases → tool name + args, never direct actions; opt-in, off by default; hooked into the background voice session only).
- `automation/agent`: `ScreenSignatureCalculator`, `ActionVerifier`, `SerializedActionExecutor` (explicit outcomes; kill-switch cancellation) on NEXA snapshots. `UiMatchers`, `UiRecipeKit`, `AutomationSelectors` (selector lists overridable via `files/automation_selectors.json`).
- `AssistantAccessibilityService.dispatchLongPress` (merged into the single service).
- `ContactRanking` (nickname + typo-tolerant candidates, proposal only) used as fallback in `ContactAndPhoneHelper.searchContacts`; typo-tolerant fallback in `open_app`.
### Changed
- `ToolRegistry` (guard wrapper + new tools + `open_url` validation), `AgentOrchestrator` (contact set, UI-tool set, confirmation context/summary/permission text for new tools), `PromptAndToolOptimizer` (new tools reachable from chat/voice), `BackgroundVoiceSessionManager` (gateway, Live tools, touch-only confirmation under Live, fast path), `LiveVoiceCoordinator` (tool declarations + tool-call execution), `AiRepository` (automation settings, YouTube key), `AiAssistantApplication` (kill-switch wiring), `SettingsScreen`/`MainActivity` (route + card), manifest (`ACCESS_COARSE_LOCATION`, `<queries>` YouTube), strings bn+en.
### Decisions (see overlap map in the Step 2 report)
- Kept NEXA's: make_call, send_whatsapp_message (AppRecipe + selectors), open_app, volume/brightness/flashlight, global_action(home), confirmation + contact picker, screenshots via `takeScreenshot`.
- NOT ported: JARVIS agent loop (`AgentTaskEngine`, `VisualTaskOrchestrator`), `UiGroundingEngine`, MediaProjection `ScreenCaptureManager` (NEXA's on-demand `takeScreenshot` needs no consent/FGS on API 30+; API 24-29 has no screenshot path — open item), `TaskProgressOverlay` (NEXA's `UiAgentIndicatorOverlay` is driven via `setUiAutomationRunning`), JARVIS's second accessibility service.
- Safety differences from JARVIS: no auto-confirmed WhatsApp send; delete verifies chat title, uses exact option text only, never falls back to "delete for me"; no hard-coded +91; only http/https URLs and guessed `.com` domains are refused; relative brightness not parsed.
### Verification
- NOT compiled, NOT run. The CommandParser regexes were exercised with a Java mirror of the rule order (same regex engine) on ~50 phrases; this found and fixed two bugs (modifier stacking in "delete my last message", Bengali combining marks in names). Unit tests are written but unexecuted.

## Unreleased — Voice engine (Step 1 of 3): Gemini Live, Owner Voice, Hands-free
### Added
- Gemini Live real-time voice as an **additional, opt-in** voice mode (Settings → Voice engine). SpeechRecognizer + TTS stays the default and is the automatic fallback (no key, no permission, owner voice not ready, audio focus denied, invalid key, retries exhausted, mic lost).
- `com.example.audio`: `MicCapture`, `AudioPlayer`, `AudioUtils`, `AudioRouteManager`, `VoiceEarcons` (ported + reworked from JARVIS).
- `com.example.voice.live`: `LiveProtocol` (message builders/parser/failure classifier), `LiveSessionStateMachine` (reconnect with exponential backoff + jitter, network pause, session resumption/goAway), `GeminiLiveSocket`, `LiveSession`, `LiveVoiceCoordinator`, `LiveVoiceSettings`.
- `com.example.voice.owner`: `OwnerVoiceMath`, `OwnerVoiceGate`, `OwnerVoiceManager`, `VoskModelManager`, `VoiceEngineViewModel`; `ui/screens/VoiceEngineSettingsScreen` (key, mode, model, voice, hands-free, owner enroll/test).
- Bundled Vosk assets `assets/vosk/{model-en-us,model-spk}` (+83 MB) with a per-file SHA-256 manifest; optional download path implemented but disabled until verified hashes are configured.
- Camera Pill / overlays reflect Gemini Live through `AssistantRuntimeController` (`isLiveVoice`, `liveStatus`, existing phases).
### Changed (existing NEXA files)
- `BackgroundVoiceSessionManager`: Live delegation point, legacy session extracted to `startLegacySession`, `endSession`/`release` wait for the live mic to be released before wake word is re-armed.
- `AssistantService`: call detection ignores our own communication mode.
- `AssistantRuntimeController.AssistantRuntimeState`: +`isLiveVoice`, +`liveStatus` (defaults keep old behaviour).
- `AiRepository`: voice-engine DataStore keys, Gemini key + `secretStore` via the existing `SecureKeyStore`.
- `AiAssistantApplication`, `MainActivity` (route), `SettingsScreen` (card), strings bn+en, backup rules (exclude `vosk/`), proguard.
### Security decisions (differences from JARVIS, on purpose)
- Key sent in the `x-goog-api-key` header (one automatic retry with `?key=` if rejected); keys/URLs/responses never logged.
- Owner Voice **holds audio locally until the speaker matches** (JARVIS streamed first, filtered the reply). Strict `distance <= threshold`; no 1.35x slack, no 2-minute lease; fail-closed.
- No second foreground service, no JARVIS tools/anti-tamper code.
### Permissions
- `MODIFY_AUDIO_SETTINGS` (AudioManager.setMode / SCO), `BLUETOOTH_CONNECT` (runtime, API 31+, optional, headset routing). Reused: RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE, WAKE_LOCK, INTERNET, ACCESS_NETWORK_STATE.
### Dependencies
- `com.alphacephei:vosk-android:0.3.75@aar`, `net.java.dev.jna:jna:5.13.0@aar`.
### Verification
- NOT compiled, NOT run (no Kotlin/Gradle/Android SDK in the authoring sandbox). Unit tests written but never executed.

## Unreleased — Bulk Import AI Models & API Keys via JSON
### New features
- "IMPORT FROM JSON" premium gradient button at the top of Models & API Keys (+ info icon showing the supported JSON format with a placeholder sample).
- Native SAF file picker (`ActivityResultContracts.OpenDocument`, no storage permission) -> validation -> preview -> confirm -> progress -> summary dialogs.
- Bulk import of multiple models with API key, baseUrl, modelName, temperature and tools/vision/streaming flags.
- Validation: file-level (empty, not JSON, unsupported structure, >2 MB, >500 entries) and per-entry (missing modelName, unknown provider without baseUrl, bad URL, temperature outside 0..2, apiKey with whitespace/control chars). Invalid entries are listed with reasons and counted as "Failed".
- Duplicate detection (provider+baseUrl+modelId+name, or same endpoint+identical API key) vs existing models and inside the file; user chooses Skip or Update.
- Import summary: Total found / Successfully imported / Updated / Skipped / Failed.
### Architecture changes
- New `data/ModelImport.kt` (pure parser, planner, models, safe file reader). `AiRepository.previewModelImport` / `applyModelImport` (single DataStore edit, mutex-serialized, key rollback on failure). `MainViewModel.modelImportState` + `onModelImportFileSelected / confirmModelImport / dismissModelImport`.
- `SecureKeyStore.trySaveEncryptedKey()` added (returns success); `saveEncryptedKey()` now delegates to it (same public behavior; in-memory cache is now only filled if the prefs commit succeeded).
### Database changes
- None (Room untouched; models still stored as JSON in DataStore key `MODELS_KEY`; keys in SecureKeyStore).
### Permission changes
- None.
### Dependency changes
- None.
### Security
- API keys are never logged; `ImportedModelEntry.toString()` masks the key; imported keys are not shown in the UI (cards keep showing only the masked last 4 chars). Parsed data is dropped from memory on dismiss/finish.
### Bug fixes / UI
- None unrelated.

## Unreleased — Camera Pill (AI Live Avatar)
### New features
- Camera Pill overlay near the camera cutout (WindowManager, TYPE_APPLICATION_OVERLAY, non-touchable, no camera access).
- Animated vector avatar: idle, blink, listening, thinking, speaking, happy, interrupted, error, offline.
- User mic bars (existing SpeechRecognizer RMS) and separate AI voice wave (synced to TTS speaking state).
- Display modes: Only While Talking (configurable hide delay 1–8 s) and Always On (while assistant service/session active).
- Settings screen "Camera Pill (AI Live Avatar)" with 11 controls incl. permission status and demo Preview.
### Architecture changes
- `AssistantRuntimeController.inAppVoiceSignal` (+ `publishInAppVoiceSignal`) bridges in-app voice mode to the pill.
- `CameraPillController` owned by `AiAssistantApplication` (single overlay instance); started from MainActivity and AssistantService (idempotent).
- `OverlayLifecycleOwner` / `overlayWindowType()` in FloatingOverlays.kt made `internal` for reuse.
### Database changes
- None (Room untouched). DataStore keys added: camera_pill_{enabled,mode,avatar_anim,visualizer,thinking_anim,speaking_anim,size,intensity,idle_delay_ms}.
### Permission changes
- None added. Uses already-declared SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE(+_MICROPHONE), ACCESS_NETWORK_STATE.
### Dependency changes
- None.
### Bug fixes / UI
- None (new feature only).
