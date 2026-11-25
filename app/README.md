# Nexa Ai — Personal Android AI Assistant

**Nexa Ai** is a Bengali-first & English-bilingual Android personal AI assistant built with Kotlin, Jetpack Compose, Material 3, Room, and multi-provider streaming + tool orchestration (`OpenAI Compatible`, `Anthropic`, `Google Gemini`).

Developer: **Developer Arpon**

---

## Key Features
- **Auto API/Model Failover**: Automatically switches across your configured backup models on `429`, `401/403`, `402`, `404`, `408`, `5xx`, or network timeouts/disconnects without duplicating completed tool calls. Includes cooldown tracking, `Retry-After` parsing, and priority reordering.
- **Offline-First Identity Responder**: Instant zero-API-call answers for identity queries (`Nexa Ai`, `Developer Arpon`) in Bengali, Banglish, and English with false-positive guards.
- **Live Web Search (`web_search`)**: Supports **Tavily**, **Brave Search**, and **SerpAPI** with automatic search provider failover and encrypted key storage (`SecureKeyStore`).
- **Long-Term Memory**: Room-backed persistent personal memory (`remember_fact`, `recall_facts`) with strict filtering rejecting passwords, OTPs, PINs, card numbers, and API keys.
- **Image & Document Attachments**: Zero-permission Android Photo Picker (`PickVisualMedia`), system camera (`TakePicture` + `FileProvider`), and SAF text/code/JSON file attachment (`<= 16 KB`), with automatic routing to a vision-capable backup model when needed.
- **Scheduled Routines & Reminders**: Doze-safe `AlarmManager` routines with `BOOT_COMPLETED` rescheduling and strict **`RiskLevel.LOW`-only** tool execution enforcement in background.
- **Usage & Token Dashboard**: Privacy-preserving request/token tracking with a 7-day Compose Canvas chart and per-model summary (no message content or API keys stored).
- **Chat Backup/Export & Import**: Export all conversations as JSON or Markdown and restore JSON backups via SAF (`CreateDocument` / `OpenDocument`), excluding all API keys.

---

## Custom Wake Word Setup ("Hey Nexa")
To enable real offline keyword spotting with **Sherpa-ONNX** (`sherpa-onnx` AAR + KWS model):
1. Add the official `sherpa-onnx` Android AAR to `app/libs/` and include it in `app/build.gradle.kts`.
2. Place a trained KWS model (e.g. `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` or a custom `"Hey Nexa"` transducer model) inside:
   `app/src/main/assets/kws/`
   containing `encoder*.onnx`, `decoder*.onnx`, `joiner*.onnx`, `tokens.txt`, and `keywords.txt`.
3. Add the phonetic pronunciation of **"HEY NEXA"** into `keywords.txt` (generated via `sherpa-onnx/scripts/kws/generate_keywords.py` or BPE tokens for `"Hey Nexa"`), and select the **"Hey Nexa"** preset chip in **Settings → Background & Wake Word**.

---

## Build & Test
```bash
gradle :app:testDebugUnitTest
gradle assembleDebug
```
