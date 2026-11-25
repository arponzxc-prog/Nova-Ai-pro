# FILE_MANIFEST

## Voice engine files (NEW, Step 1)
- audio/: AudioUtils.kt, MicCapture.kt, AudioPlayer.kt, AudioRouteManager.kt, VoiceEarcons.kt
- voice/live/: LiveProtocol.kt, LiveSessionStateMachine.kt, GeminiLiveSocket.kt, LiveSession.kt, LiveVoiceCoordinator.kt, LiveVoiceSettings.kt
- voice/owner/: OwnerVoiceMath.kt, OwnerVoiceGate.kt, OwnerVoiceManager.kt, VoskModelManager.kt, VoiceEngineViewModel.kt
- ui/screens/VoiceEngineSettingsScreen.kt
- assets/vosk/{model-en-us,model-spk,manifest.json}
- tests: AudioUtilsTest, LiveSessionStateMachineTest, LiveProtocolTest, OwnerVoiceTest, VoiceEnginePlumbingTest
- root: THIRD_PARTY_NOTICES.md, LICENSE-JARVIS-SOURCE.md; docs/VOICE_ENGINE_TEST_CHECKLIST.md
## Automation files (NEW, Step 2)
- automation/core/: AutomationSwitches.kt, AutomationSelectors.kt, AutomationSettingsViewModel.kt, UiMatchers.kt, UiRecipeKit.kt, ToolSummaries.kt
- automation/phone/DomainNormalizer.kt; automation/command/{CommandParser,CommandRouter}.kt; automation/contacts/ContactRanking.kt
- automation/agent/{ScreenSignature,ActionVerifier,SerializedActionExecutor}.kt
- automation/whatsapp/WhatsAppRecipes.kt; automation/youtube/{YouTubeApiClient,YouTubeAutomation}.kt
- tools/AutomationTools.kt, tools/ToolGateway.kt; voice/live/LiveToolDeclarations.kt; ui/screens/AutomationControlScreen.kt
- tests: AutomationCoreTest.kt; docs/AUTOMATION_TEST_CHECKLIST.md
## UI + branding files (Step 3)
- ui/theme/{NovaTheme,ThemeSelectionSheet}.kt; ui/hud/{OrbState,NovaOrb,NovaComponents,HudHomeScreen}.kt; ui/about/{AboutContent,AboutScreens}.kt
- assets/nova_hud/{orb.html,three.min.js}; assets/legal/{THIRD_PARTY_NOTICES.md,LICENSE-JARVIS-SOURCE.md}
- res: drawable/ic_launcher_{foreground,background}.xml (new vectors), mipmap-*/ic_launcher*.png (regenerated); deleted drawable/ai_assistant_icon_*.jpg
- test: NovaUiTest.kt; docs/UI_TEST_CHECKLIST.md
## Modified for Step 3
- ui/theme/Theme.kt, MainActivity.kt, ui/screens/SettingsScreen.kt, data/AiRepository.kt, tools/{IdentityResponder,AgentOrchestrator}.kt, data/ChatBackupManager.kt, network/AiProviders.kt, model/Models.kt, assistant/RoutineAlarmReceiver.kt, camerapill/{CameraPillContent,CameraPillOverlay}.kt, res/values{,-en}/strings.xml, README.md, THIRD_PARTY_NOTICES.md, tests (IdentityResponderTest, MemoryBackupAndFeatureTest, ExampleRobolectricTest)

## Modified for Step 2
- tools/{ToolRegistry,AgentOrchestrator,PromptAndToolOptimizer,SystemTools,ContactAndPhoneHelper}.kt, accessibility/AssistantAccessibilityService.kt, assistant/BackgroundVoiceSessionManager.kt, voice/live/LiveVoiceCoordinator.kt, data/AiRepository.kt, AiAssistantApplication.kt, MainActivity.kt, ui/screens/SettingsScreen.kt, AndroidManifest.xml, res/values{,-en}/strings.xml, THIRD_PARTY_NOTICES.md
- Deleted/renamed: none.

## Modified for Step 1
- assistant/BackgroundVoiceSessionManager.kt, assistant/AssistantService.kt, assistant/AssistantRuntimeController.kt, data/AiRepository.kt, AiAssistantApplication.kt, MainActivity.kt, ui/screens/SettingsScreen.kt, AndroidManifest.xml, app/build.gradle.kts, app/proguard-rules.pro, res/values{,-en}/strings.xml, res/xml/{backup_rules,data_extraction_rules}.xml
- Deleted/renamed: none.

## Important directories
- app/src/main/java/com/example/{assistant,accessibility,camerapill,data,network,security,tools,ui,viewmodel,voice,model}
- app/src/main/res/{values,values-en,xml,font,drawable,mipmap-*}
- app/src/test/java/com/example (JVM/Robolectric tests)

## Camera Pill files (NEW) — app/src/main/java/com/example/camerapill/
- CameraPillModels.kt — settings data class, modes, avatar/source phases, `PillVoiceSignal`, pure `CameraPillLogic`.
- CutoutPlacement.kt — Android-free cutout→window position math (notch, punch-hole, side cutout, none).
- PillAvatar.kt — Canvas avatar + `blinkOpenness` + `rememberPillClock` (draw-phase-only animation clock).
- PillVisualizers.kt — `UserVoiceBars` (real RMS) and `AiVoiceWave` (procedural, TTS-state synced).
- CameraPillContent.kt — glass pill UI, expand/collapse, theme-aware, TalkBack description.
- CameraPillOverlay.kt — WindowManager host (flags, cutout mode, reposition, hide/cleanup).
- CameraPillController.kt — state machine/lifecycle: settings+voice signals+connectivity+permission → UI model + overlay show/hide; preview; rotation/display listeners.
- ui/screens/CameraPillSettingsScreen.kt — settings UI (NEW).
- app/src/test/.../CameraPillLogicTest.kt — unit tests (NEW).

## Modified files and purpose
- data/AiRepository.kt — 9 DataStore keys, `cameraPillSettingsFlow`, `saveCameraPillSettings`.
- viewmodel/MainViewModel.kt — init-time publisher (voiceModeState + speech RMS + TTS isSpeaking → signal); `cameraPillSettings`, `updateCameraPillSettings`, `previewCameraPill`, `refreshCameraPill`, `cameraPillOverlayVisible`.
- assistant/AssistantRuntimeController.kt — `inAppVoiceSignal`.
- assistant/AssistantService.kt — `app.cameraPillController.start()` in onCreate.
- assistant/FloatingOverlays.kt — visibility change only.
- AiAssistantApplication.kt — `cameraPillController` (lazy).
- MainActivity.kt — start() call, route `camera_pill_settings`, import.
- ui/screens/SettingsScreen.kt — entry card + `onNavigateToCameraPill` param (default `{}`).
- res/values(-en)/strings.xml — `pill_*` strings.

## Bulk JSON Import (this milestone)
NEW
- app/src/main/java/com/example/data/ModelImport.kt — `ModelImportParser` (validation), `ModelImportPlanner` (ADD/UPDATE/SKIP, duplicate rules), result/preview/summary models, `ModelImportIo` (size-capped file read).
- app/src/main/java/com/example/ui/components/ModelImportComponents.kt — `ImportModelsButton`, `ImportFormatHelpDialog`, `ModelImportDialogHost` (reading/preview/progress/summary/error).
- app/src/test/java/com/example/ModelImportTest.kt — parser + planner unit tests (Robolectric runner for org.json).
- docs/nexa_models_import_sample.json — sample import file (placeholder keys only).
MODIFIED
- data/AiRepository.kt — `previewModelImport`, `applyModelImport`, `rollbackImportedKeys`, import Mutex.
- security/SecureKeyStore.kt — `trySaveEncryptedKey`; `saveEncryptedKey` delegates.
- viewmodel/MainViewModel.kt — `ModelImportUiState`, `modelImportState`, import actions.
- ui/screens/ModelListScreen.kt — import button, SAF launcher, dialog host, help dialog (list moved into a weighted Box under the button).
- res/values/strings.xml, res/values-en/strings.xml — 51 `import_*` strings each (bn + en parity).
