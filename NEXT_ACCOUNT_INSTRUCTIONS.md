# NEXT_ACCOUNT_INSTRUCTIONS.md

## Most recent task (JSON bulk import) — what the previous account was asked to do
Add an IMPORT button to Models & API Keys: native file picker -> JSON -> validated bulk import of models + API keys/baseUrl/modelId/config, duplicate detection (skip/update), import summary (total/imported/updated/skipped/failed), premium dialogs with progress, secure key handling, no change to existing config on failure.

## What was actually implemented (source only)
Everything listed in FILE_MANIFEST.md "Bulk JSON Import". **Nothing compiled, no tests run, no device run** (sandbox had no Android SDK/Gradle/kotlinc/network).

## FIRST ACTIONS (in order)
1. Verify these exist: `data/ModelImport.kt`, `ui/components/ModelImportComponents.kt`, `ModelImportTest.kt`; `ModelListScreen.kt` contains `ImportModelsButton`.
2. Build (`./gradlew assembleDebug`) and fix compile errors. Likeliest friction points:
   - `ModelImportComponents.kt`: icon names (`Outlined.FileUpload/ListAlt/Sync/SkipNext/Info/ErrorOutline` — some may be deprecated/AutoMirrored in the icons version used), `LinearProgressIndicator(progress = { })`, `surfaceContainerHighest`.
   - `AiRepository.applyModelImport`: `return@withLock` inside `try/catch` within `Mutex.withLock`, lambda captured `var` counters inside `dataStore.edit`.
   - `ModelImport.kt`: `data object` (needs Kotlin >= 1.9), private sealed interface inside object.
   - `ModelListScreen.kt`: Column/Box restructure (`Modifier.weight` inside ColumnScope).
3. Run `./gradlew testDebugUnitTest --tests "com.example.ModelImportTest"` (10 tests; expected outcomes were reasoned by hand, not executed).
4. Device test matrix: valid multi-model file; file with duplicates (Skip and Update); duplicates inside the file; invalid JSON; empty file; wrong structure; >2 MB file; entries with bad URL / bad temperature / key with space; entry without apiKey; kill-app-mid-import is not expected to corrupt state; picker on Samsung/Xiaomi/Pixel file managers (.json shown as json/octet-stream?); rotation during preview/importing; Android 7, 10, 14; confirm keys never appear in Logcat and cards show only masked last 4; confirm failover list picks up imported models; confirm an import with a forced storage failure leaves models/keys unchanged.
5. UI polish after seeing it on device (button gradient/contrast in light mode, dialog length with many rejected entries).

## Files needing further work
ui/components/ModelImportComponents.kt (visual QA), data/AiRepository.kt (import section ~L470-640: verify rollback on real failure).

## Must not change
Existing signingConfigs in app/build.gradle.kts; existing `upsertModelConfig` / `setActiveModel` / `deleteModelConfig` behavior; the import must never change the active model, never log keys, and must stay all-or-nothing on storage failure; Bengali+English string parity; plus all "Must not change" items from the Camera Pill task below.

## Design decisions to keep (or consciously revisit)
- Duplicate rule: provider+baseUrl+modelId+name (case-insens., trailing slash ignored) OR same endpoint with identical API key. Different name + different key = new config (multi-key failover).
- Non-openai/anthropic/gemini providers without baseUrl are rejected (avoids sending a key to the wrong endpoint).
- Pending: optional "Export models to JSON" (would need a decision on whether to include keys; default should be NO keys).

---
# Camera Pill task notes (still valid; still unverified)
## What the previous account was asked to do
Implement the Camera Pill (AI Live Avatar): cutout-anchored overlay, animated avatar, real-time mic visualizer + AI voice visualizer, AI-state sync (IDLE/LISTENING/THINKING/SPEAKING/INTERRUPTED/ERROR/OFFLINE), settings (11 controls), overlay service architecture, privacy rules.

## What was actually implemented (source only)
Everything in FILE_MANIFEST.md "Camera Pill files". All 11 settings controls exist and are consumed by the controller/overlay. **No compile, no tests, no device run happened** (sandbox had no SDK/Gradle/network).

## FIRST ACTIONS (in order)
1. Verify the ZIP contains `app/src/main/java/com/example/camerapill/` (7 files) and `CameraPillSettingsScreen.kt`.
2. If an Android SDK/Gradle is available: build, fix compile errors, run `CameraPillLogicTest`. Likely friction points to check first:
   - `CameraPillOverlay` (WindowMetrics/insets API use, `title`, destructuring of private data class)
   - `CameraPillContent` (`shadow(ambientColor/spotColor)`, modifier order)
   - `CameraPillController.start()` (5-flow `combine` + chained `combine`s)
   - `MainViewModel` init publisher imports (`combine`, `first`, `distinctUntilChanged` were added manually)
3. Device test matrix: overlay permission grant/deny/revoke; Only While Talking (hide delay); Always On with/without background listening; in-app VoiceModeScreen; background session over another app; rotation; punch-hole/notch/no-cutout/foldable; Android 7, 10, 12, 14; offline toggle; interruption; battery/memory (Android Studio profiler).
4. Tune visuals (avatar proportions, glow) after seeing it on a device.

## Files needing further work
camerapill/CameraPillOverlay.kt (cutout reading on API 28–29 re-places after attach — verify), camerapill/PillAvatar.kt (visual polish).

## Must not change
Existing signingConfigs in app/build.gradle.kts; no extra mic sessions for the pill; no camera access; Bengali+English string parity; `AssistantService` mic FGS + notification must stay visible.

## Special notes
- TTS amplitude is unavailable by design; do not add `android.media.audiofx.Visualizer` (needs RECORD_AUDIO semantics + privacy concerns) without explicit user approval.
- True 3D avatar would need an asset/runtime (Rive/Lottie/Filament) — not added (no new dependencies).
- (Outdated note removed: gradlew + wrapper jar ARE in the ZIP.)
