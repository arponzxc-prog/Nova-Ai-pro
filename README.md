# NOVA AI

NOVA AI is an Android voice and chat assistant (Kotlin, Jetpack Compose) with multi-provider AI and failover, a tool system, wake word,
routines, Gemini Live real-time voice, Owner Voice, and phone/WhatsApp/YouTube automation.

**Developer: ARPON**

## Build

1. Install Android Studio (recent stable) with the Android SDK (compileSdk 36) and a JDK that Android Studio supports.
2. Open this folder in Android Studio and let Gradle sync. Vosk and JNA AARs are resolved from Maven. The code base has not been
   synced or compiled since the Step 1-3 merges, so expect to fix small compile errors on the first build.
3. The build uses the Secrets Gradle Plugin with `.env` / `.env.example` (see `app/build.gradle.kts`). Copy `.env.example` to `.env`
   if the build asks for it. Provider API keys for the assistant itself are entered in the app and stored encrypted on the device.
4. Signing: `app/build.gradle.kts` contains signing configs marked "do not remove or modify". The old AI Studio README told you to delete
   one `signingConfig` line; that advice is out of date for this repository, so leave them as they are unless you know why.
5. `./gradlew testDebugUnitTest`, then `./gradlew assembleDebug`.

The Android `applicationId` (`com.aistudio.aiassistant.pxqtr`) and Kotlin package names were deliberately left unchanged by the NOVA AI rename (see PROJECT_HANDOVER.md).

## Credits

UI design and voice/automation foundations based on JARVIS AI by Tech GPTx (https://github.com/techgptx/Playlist), licensed under TAMCL v1.0.
Content creators must credit Tech GPTx in any video using this code. The Tech GPTx attribution must not be removed from code, UI or docs.
The licence text for the JARVIS code is still missing from this repository — see `LICENSE-JARVIS-SOURCE.md`.

Other components: Vosk (Apache-2.0), JNA, OkHttp, Three.js (MIT). Full list: `THIRD_PARTY_NOTICES.md` (also shown in-app under
Settings → About NOVA AI → notices).

New/original NOVA AI code: © ARPON. Project name as stated by the maintainer: ARPON X THE TIGER.
