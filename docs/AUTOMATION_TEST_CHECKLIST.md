# Automation (Step 2) — manual device checklist (nothing below has been run)
Setup: device with WhatsApp + YouTube installed, accessibility service enabled, contacts/call/location permissions, a second WhatsApp contact you may message, headset optional.
Each row: run via **text chat**, via **voice (standard)**, and via **Gemini Live**.
1. Kill switch: turn ON mid-task → running UI task stops, every command answers with the kill-switch message, Live tools stop being offered next session. OFF → works again. Restart app with it ON → still blocked from the first command.
2. Feature toggles: disable YouTube → youtube_* refused, WhatsApp still works.
3. Open app (also with a typo: "yutube"), call (contact name, ambiguous name → picker, number), flashlight on/off, volume set/up/down/mute, brightness set (WRITE_SETTINGS flow).
4. Media: play music in any app → "pause", "resume", "next", "previous" (check the reply says whether pause was observed).
5. Web: "open github.com and vercel.com" (two tabs), "google best pizza", a bare unknown word → refused with a request for the full address; `javascript:` / `intent:` input refused.
6. Weather: permission prompt → coarse location only; no last-known fix → clear message; airplane mode → clear failure.
7. YouTube: search; play with and without API key; vague "play it on youtube" refused; subscribe (confirmation, verify result text, already-subscribed case); fullscreen with controls hidden/visible.
8. WhatsApp message (existing), call voice + video (confirmation shown with contact+number, call actually starts), contact without WhatsApp → clean failure.
9. WhatsApp delete: wrong chat open → refused; last bubble outgoing; "for me" and "for everyone"; message older than the window (no "everyone" option) → nothing deleted and NO fallback to "for me"; check the chat afterwards.
10. Confirmations: every HIGH tool shows the card; Cancel/timeout executes nothing; under Gemini Live confirmation is touch-only and the mic/TTS are not taken over.
11. Secure screens: open a banking/password screen (and a screen-security-blocked app) → read/click/type/WhatsApp/YouTube recipes refuse.
12. Disabled tools (Settings → tools) still honoured for every new tool.
13. Robustness: disable accessibility mid-recipe, rotate/navigate away mid-recipe, kill app mid-recipe, WhatsApp/YouTube not installed, WhatsApp Business only, Android 8 / 10 / 14 / 16, Samsung / Xiaomi / Oppo.
14. Selector override: put a bad selector in `files/automation_selectors.json` → recipe fails gracefully with a message; fix the file → works without rebuild.
15. Logs: no API key, URL query, message text or phone number in logcat.
