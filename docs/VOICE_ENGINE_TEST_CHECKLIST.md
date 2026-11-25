# Voice engine — manual device checklist (nothing below has been run)
Setup: real device (API 31+ and one older if possible), Gemini key, headset (wired + Bluetooth), second person for Owner Voice.
1. Build/sync succeeds; `./gradlew testDebugUnitTest` passes; existing app screens unchanged.
2. Settings → Voice engine: save key (shown masked); Standard mode still works exactly as before (wake word → answer → TTS).
3. Switch to Gemini Live; say wake word → Camera Pill shows connecting → listening; speak → audio reply plays; pill shows speaking; interrupt with headset → reply cuts off.
4. No key / wrong key / airplane mode at start / bad model id → app falls back to standard voice with a visible notice; no crash.
5. Airplane mode toggled mid-session → "waiting for network" → restore → session resumes; kill Wi-Fi repeatedly → stops after the retry budget and falls back.
6. Incoming call / other app grabs audio focus → Live pauses/ends cleanly; wake word re-arms after.
7. Screen off + hands-free on: session survives ≥ 5 min; with hands-free off it ends after ~45 s idle.
8. Bluetooth headset connect/disconnect mid-session: audio follows; after the session, speakerphone/mode are back to what they were (check music playback afterwards).
9. Revoke mic permission while idle and while in session; force-stop and START_STICKY restart: no crash, wake word re-arms, no stuck notification state.
10. Owner Voice: install models (progress), record 3 samples, Test shows a distance; enable; owner speech is answered, a second person is not, and (via a packet capture or proxy) rejected speech is not uploaded. Calibrate strictness on your devices.
11. Models missing/deleted with Owner Voice enabled → Live refuses to start, standard voice runs with notice.
12. Leak checks: Android Studio profiler / `adb shell dumpsys media.audio_flinger` after 20 start/stop cycles — no lingering AudioRecord/AudioTrack/threads named `nexa-live-*`.
13. Logcat contains no API key, no `key=` URL and no transcript text.
