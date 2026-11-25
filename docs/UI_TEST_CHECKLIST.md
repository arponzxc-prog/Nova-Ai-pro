# NOVA AI UI + branding — manual device checklist (nothing below has been run)
1. Launcher: icon, label "NOVA AI" (bn and en locale), adaptive/round/themed (monochrome) icon, Quick Settings tile label, boot notification, accessibility + notification-access service names in system settings.
2. Settings → Appearance: Auto/Dark/Light/Red/Sunset switch live, survive app restart and process kill; Auto follows the old light/dark/system setting and system dark mode.
3. Every screen in each of the four themes: text readable, buttons labelled, dialogs/sheets not see-through, switches/chips visible. Look especially at the camera-pill settings and screens with hard-coded colours.
4. Font scale 200% and Bangla text (Hind Siliguri) on Settings, About, HUD; RTL layout sanity; TalkBack reads orb state, dock tabs, mic button.
5. HUD home: orb idle → wake word/voice → listening → thinking → speaking; Gemini Live connecting/reconnecting shows "thinking"; audio level moves the orb; app to background/screen off → rendering pauses (check GPU/CPU), return → resumes.
6. Fallbacks: enable battery saver / "remove animations" / low-RAM device / disable or downgrade Android System WebView → Canvas orb appears, no crash. Kill the WebView renderer process → falls back.
7. WebView hardening: with a proxy confirm no network traffic from the orb; `chrome://inspect` shows nothing in release; rotating, folding and split-screen keep working.
8. About: exact lines "NOVA AI — Developer: ARPON" and the Tech GPTx / TAMCL v1.0 credit; notices screen shows THIRD_PARTY_NOTICES + licence placeholder; link opens the browser.
9. Identity: ask "who are you / who made you" in bn, banglish, en, text + voice → NOVA AI / ARPON; "Hey Nexa" wake phrase still works.
10. Export chats/backup → file names `nova_*`; import an old `nexa_*` backup file still works.
11. Regression pass: chat, provider failover, tools, routines, notifications, memory, camera pill, Step 1 voice, Step 2 automation.
