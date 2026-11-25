# DEVELOPMENT_PROGRESS.md

## Task: UI + branding Step 3 (current, PARTIAL)
### Status: PARTIALLY IMPLEMENTED — NOT BUILD-VERIFIED, NOT RUNTIME-TESTED
- Done: theme tokens + persistence + sheet, orb (WebView + Canvas fallback) + HUD home, About/notices, rename + icon, identity + tests.
- Not done: dock navigation app-wide, chat restyle, overlay avatars, Privacy Shield / Persona / API-connection screens, per-screen restyle review, "Updates" tab.
- Verified: contrast numbers (Python), strings parity (637/637), icon rendered and inspected. Everything else unverified.

## Task: Automation Step 2 — phone control, YouTube/WhatsApp, screen-agent blocks (current)
### Status: IMPLEMENTED IN SOURCE — NOT BUILD-VERIFIED, NOT RUNTIME-TESTED
- Verified: parser regexes run through a Java mirror (found/fixed 2 bugs); bn/en string parity; XML validity; referenced NEXA members exist (grep).
- NOT verified: compilation, unit tests, every device behaviour (calls, WhatsApp UI selectors, YouTube UI, media keys, weather), Live tool calling with Gemini, OEM quirks.
### Remaining
Build + tests + device checklist; MediaProjection fallback for API 24-29 (needs your decision); licence text; Step 3.

## Task: Voice engine Step 1 — Gemini Live / Owner Voice / Hands-free (current)
### Status: IMPLEMENTED IN SOURCE — NOT BUILD-VERIFIED, NOT RUNTIME-TESTED
### Verified vs unverified
- Verified: Bengali/English string parity (68 keys each) and both strings.xml parse as XML; Vosk asset checksums generated from the real files; test-case arithmetic for the gate/state machine/maths was hand-traced.
- NOT verified: compilation of any Kotlin file, Gradle sync (Vosk/JNA resolution), unit tests, any device behaviour, Gemini model id availability, `x-goog-api-key` header acceptance on the WebSocket handshake, Owner Voice thresholds, Bluetooth routing, screen-off survival.
### Completed
Audio layer; protocol + state machine + session; owner voice + gate + Vosk installer; service integration; settings UI; tests; docs.
### Remaining
Build, run tests, device checklist, resolve JARVIS licensing, decide APK-size strategy (see CHANGELOG).

## Task: Bulk Import AI Models & API Keys via JSON (current)
### Status: IMPLEMENTED IN SOURCE — NOT BUILD-VERIFIED, NOT RUNTIME-TESTED
| Item | Implemented | Build verified | Runtime tested | Needs device testing |
|---|---|---|---|---|
| IMPORT button (top of Models & API Keys) + help dialog | yes | no | no | yes (visual) |
| Native SAF file picker (OpenDocument, json/text/octet-stream) | yes | no | no | yes (various file managers) |
| Parser/validator (file-level + per-entry) | yes | no | no | JUnit run needed |
| Duplicate detection + Skip/Update | yes | no | no | JUnit run needed |
| Atomic apply + key rollback | yes | no | no | yes (incl. forced failure) |
| Preview / progress / summary / error dialogs | yes | no | no | yes |
| bn + en strings (51 keys each, XML well-formed, no dups, R refs resolved) | yes | n/a | n/a | n/a |
| `ModelImportTest` (10 tests) | written | not run | not run | run `./gradlew testDebugUnitTest --tests "com.example.ModelImportTest"` |

### Completed steps
1. Inspected ZIP (188 entries), handover docs, Gradle, ModelListScreen, AddEditModelScreen, AiRepository model persistence, SecureKeyStore, MainViewModel model APIs, nav.
2. Wrote `data/ModelImport.kt`; added repository preview/apply with rollback; `SecureKeyStore.trySaveEncryptedKey`.
3. ViewModel state machine; UI components; ModelListScreen integration; strings (bn/en).
4. Unit tests; docs; sample JSON.

### Static checks actually performed
Brace/paren balance on all touched Kotlin files; strings XML parse + duplicate-name check; every `R.string.*` used in new code exists in both locales; format-arg parity bn/en; sample JSON parses. No compilation (no kotlinc/SDK/Gradle/network).

### Remaining / incomplete
- Compile + fix errors; run tests; device test (see NEXT_ACCOUNT_INSTRUCTIONS.md).
- No snackbar/toast beyond the in-dialog success/error states.
- Import does not handle exporting (no "Export to JSON" yet).

### Modified files
data/AiRepository.kt, security/SecureKeyStore.kt, viewmodel/MainViewModel.kt, ui/screens/ModelListScreen.kt, res/values/strings.xml, res/values-en/strings.xml, 5 handover .md files.
### New files
data/ModelImport.kt, ui/components/ModelImportComponents.kt, test/.../ModelImportTest.kt, docs/nexa_models_import_sample.json.
### Deleted files
None.

---
# Previous task (kept for reference)

## Task
Camera Pill (AI Live Avatar) overlay + animated avatar + voice visualizer + settings (source: uploaded task .txt).

## Status: IMPLEMENTED IN SOURCE — NOT BUILD-VERIFIED, NOT RUNTIME-TESTED
The authoring sandbox had no Android SDK, no Gradle, no network. Nothing has been compiled or run on a device.

## Verification matrix
| Item | Implemented | Build verified | Runtime tested | Needs device testing |
|---|---|---|---|---|
| Settings keys/flows (DataStore) | yes | no | no | yes |
| Pure logic (state mapping, show/hide rules, cutout placement) | yes | no | no (algorithm mirrored in Python: expected values match) | JUnit run needed |
| Avatar canvas (idle/blink/listen/think/speak/happy/error/offline) | yes | no | no | yes (visual QA) |
| User mic bars (real RMS) / AI voice wave (procedural) | yes | no | no | yes |
| WindowManager overlay, cutout placement, rotation/display listeners | yes | no | no | **yes, essential (OEM cutout behaviour varies)** |
| Controller: modes, hold delay, interrupted/happy, offline, preview | yes | no | no | yes |
| Settings screen (11 controls) + nav + Settings entry | yes | no | no | yes |
| Bengali + English strings (XML well-formed, no duplicate names) | yes | n/a | n/a | n/a |
| Unit tests `CameraPillLogicTest` (12 tests) | written | not run | not run | run `./gradlew testDebugUnitTest` |

## Completed steps
1. Inspected ZIP (170 files), manifest, Gradle, voice stack, overlays, settings/nav patterns. No prior handover docs existed.
2. M1: DataStore settings + models + pure logic + controller bridge + in-app publisher.
3. M2: avatar, visualizers, pill UI, WindowManager overlay, CameraPillController.
4. M3: settings screen, nav route `camera_pill_settings`, Settings card, strings (bn/en), unit tests.
5. M4: documentation + ZIP.

## Remaining / incomplete
- Compile, fix any errors, run unit tests (see NEXT_ACCOUNT_INSTRUCTIONS.md).
- Device tests on notch / punch-hole / no-cutout / foldable / landscape; Android 7–15.
- Optional true 3D avatar asset (not included).
- Pill is intentionally non-touchable (FLAG_NOT_TOUCHABLE); no tap actions.

## Modified files
AiAssistantApplication.kt, MainActivity.kt, data/AiRepository.kt, viewmodel/MainViewModel.kt,
assistant/AssistantRuntimeController.kt, assistant/AssistantService.kt (one line),
assistant/FloatingOverlays.kt (`private`→`internal` on OverlayLifecycleOwner, overlayWindowType),
ui/screens/SettingsScreen.kt, res/values/strings.xml, res/values-en/strings.xml.

## New files
camerapill/{CameraPillModels, CutoutPlacement, PillAvatar, PillVisualizers, CameraPillContent, CameraPillOverlay, CameraPillController}.kt,
ui/screens/CameraPillSettingsScreen.kt, test/.../CameraPillLogicTest.kt, and the 5 handover .md files.

## Deleted files
None.

## Testing results
Only static checks: brace/paren balance on all touched Kotlin files, XML well-formedness + duplicate-name check on both strings files, Python mirror of CutoutPlacement. No compilation, no JUnit run, no device run.
