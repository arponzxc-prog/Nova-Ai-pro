# THIRD_PARTY_NOTICES

## 0. NOVA AI
- Developer of the new/original NOVA AI code: **ARPON** (© ARPON).
- Project/maintainer name as stated by the maintainer: **ARPON X THE TIGER**. This is the maintainer's own statement about
  the NOVA AI project; it is NOT a statement about who owns or licenses the JARVIS code (see section 1).
- The app does not contain, and must not be given, any "verified developer" or "official JARVIS build" claim or check.

## 1. JARVIS (source of the ported voice engine, automation and UI)
Portions of `com.example.audio.*`, `com.example.voice.live.*` and `com.example.voice.owner.*` are derived from the
JARVIS project (package `com.jarvis.assistant`). Files carrying that lineage say so in their header.

Attribution **as it appears inside the JARVIS archive** (`security/DeveloperInfo.kt`, `security/SecurityManager.kt`):
- "© 2026 Tech GPTx. All Rights Reserved."
- License named in the code: "TAMCL v1.0 — Content creators MUST credit Tech GPTx in any video using this code."
- Repository named in the code: https://github.com/techgptx/Playlist

Attribution **as stated by the NEXA maintainer** for this merge: the JARVIS source is under "ARPON X THE TIGER".

**These two statements do not match and the archive contains no LICENSE file.** See `LICENSE-JARVIS-SOURCE.md`.
Until the maintainer confirms the real owner and license terms, treat the derived files as "rights holder to be confirmed",
do not distribute builds publicly, and do not remove the header comments.

Not ported (deliberately): JARVIS `SecurityManager` / `DeveloperInfo` (anti-tamper / package-lock logic), UI, tools, notifications, memory.

### Step 2 additions (automation)
Derived from JARVIS (same attribution caveat as above): `automation/phone/DomainNormalizer.kt`, `automation/command/CommandParser.kt`
(phrase ideas only), `automation/contacts/ContactRanking.kt`, `automation/agent/{ScreenSignature,ActionVerifier,SerializedActionExecutor}.kt`,
`automation/whatsapp/WhatsAppRecipes.kt`, `automation/youtube/*`, `tools/AutomationTools.kt` (media/web/weather handling).
The Step 2 task statement describes the JARVIS license as "TAMCL v1.0 by ARPON X THE TIGER"; the archive itself names
"TAMCL v1.0" with "Tech GPTx" as the credited party. TAMCL requires credit to Tech GPTx for video content made with the code —
that credit is preserved here and must not be removed. The license text itself is still missing (see `LICENSE-JARVIS-SOURCE.md`).
Not ported: `AgentTaskEngine`, `VisualTaskOrchestrator`, `UiGroundingEngine`, `ScreenCaptureManager`, `TaskProgressOverlay`,
`JarvisAccessibilityService` (NEXA's single accessibility service replaces it), `AppLauncher`, `ActivityLauncherHelper`,
`ContactCaller`, `BuiltInChromeEngine`, `PhoneControlManager`, `CommandExecutor`, `ContactResolver` (replaced by NEXA equivalents).

External service: Open-Meteo (https://open-meteo.com) is queried by `get_weather_location` with coordinates rounded to ~1 km;
no key. Its free tier is for non-commercial use — check terms before commercial distribution.
YouTube Data API v3 (optional, user's own key): subject to the YouTube API Services Terms.

## 2. Vosk (offline speech models and library)
- Library `com.alphacephei:vosk-android:0.3.75` — Apache License 2.0, © Alpha Cephei Inc.
- Bundled model folders `assets/vosk/model-en-us` and `assets/vosk/model-spk` (copied unchanged from the JARVIS archive; each
  file's SHA-256 is recorded in `assets/vosk/manifest.json`). Their README files name Alpha Cephei Inc. as author
  (small English model and speaker-identification model; Apache 2.0 per upstream). **Verify the exact model names/licenses
  against https://alphacephei.com/vosk/models before shipping** — only the in-archive README text was checked.

## 3. JNA
`net.java.dev.jna:jna:5.13.0` — dual-licensed LGPL-2.1-or-later / Apache-2.0. Used unmodified as a dynamic dependency.

## 4. OkHttp
Already a NEXA dependency (Apache-2.0); reused for the Live WebSocket.

## 5. Google Gemini Live API
Network service used at runtime with the user's own API key; subject to Google's Gemini API terms.

## 6. JARVIS UI assets (Step 3)
Ported/adapted from JARVIS AI by Tech GPTx (https://github.com/techgptx/Playlist), TAMCL v1.0 — credit to Tech GPTx must be kept:
- `assets/nova_hud/orb.html` — adapted from JARVIS `assets/orb.html` (attribution header added; 30 fps cap, pause switch,
  web-microphone path disabled, local-only).
- `assets/nova_hud/three.min.js` — Three.js r128, MIT, © 2010-2021 Three.js Authors, unchanged (license header is inside the file).
- Theme palettes (dark / light / red / sunset) in `ui/theme/NovaTheme.kt` — token values from JARVIS `Theme.JarvisAssistant.*`,
  contrast-corrected. Liquid-glow background, glass card and curved dock designs rebuilt in Compose (`ui/hud/NovaComponents.kt`);
  no JARVIS XML or drawable is copied.
Not ported in this step (so no assets copied): overlay avatars, JARVIS logo PNG, JARVIS fonts/images, Privacy Shield / Persona /
API-connection activities, `AppIdentityVerifier` / `DeveloperInfo` identity checks.
