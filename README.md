# Custom TTS Service for Android

An Android Text-to-Speech (TTS) engine that connects to virtually any HTTP TTS API — OpenAI-compatible backends, [Speechify](https://docs.speechify.ai), [xAI](https://x.ai), or your own self-hosted service — and exposes it as a standard system-wide TTS engine on your Android device.

---

## Features

* Integrates as a standard Android TTS Engine (selectable in Android Settings → Text-to-Speech).
* **Multiple API request styles**:
    * **OpenAI** — `{ model, input, voice, response_format, speed }`
    * **Speechify** — `{ input, voice_id, audio_format, model }` (base64-JSON responses handled automatically)
    * **xAI** — `{ text, voice_id, language }` (no model required)
* **Model is optional** — blank fields are omitted from the request, so APIs without a model (like xAI) just work.
* **API Key is optional** — works without authentication for local servers.
* **MP3 / OGG / Opus / AAC / FLAC decoding** via the platform `MediaCodec`, plus native WAV and raw PCM support. The actual format is auto-detected from the response bytes.
* **Auto-save generations** — optionally save every generated clip as an audio file to a folder of your choice (picked via the system folder picker).
* **Quick-fill presets** for OpenAI, Speechify and xAI.
* **Direct link** to Android TTS engine selection from the main screen.
* Settings are persisted locally using Jetpack DataStore.

---

## Setup

### 1. Install the app

Download the APK from the **Build APK** workflow artifacts (Actions tab) or from [Releases](../../releases), or build from source.

### 2. Select as TTS Engine

Open the app and tap **"Open Android TTS Settings"**, then set *CustomTTS* as your preferred engine.

### 3. Configure the backend

Tap the ⚙️ icon to open Settings. Use a preset or fill in manually:

| Field | OpenAI | Speechify | xAI |
|-------|--------|-----------|-----|
| API Style | OpenAI | Speechify | xAI |
| Backend URL | `https://api.openai.com/v1/audio/speech` | `https://api.speechify.ai/v1/audio/speech` | `https://api.x.ai/v1/tts` |
| API Key | Your OpenAI key | Your Speechify key (`sk_…`) | Your xAI key |
| Model | `tts-1` / `tts-1-hd` | `simba-3.2` | *(leave empty)* |
| Voice | `alloy`, `nova`, … | `harper_32`, … | `eve`, … |
| Language | *(empty)* | *(empty)* | `en` |
| Format | `mp3` / `wav` | `mp3` | `mp3` |

Tap **Save**.

Any other endpoint that follows one of these three request shapes works too — pick the closest style and point the URL at your server.

### 4. (Optional) Auto-save generations

Enable **Auto-save generations** in Settings and pick a destination folder. Every generated clip is saved there with a timestamped filename (e.g. `tts_20260708_142530.mp3`). If no folder is chosen, files go to the app's own storage (`Android/data/com.example.CustomTts/files/generations`).

---

## Building the APK

Every push triggers the **Build APK** GitHub Actions workflow, which uploads a debug APK as a build artifact. You can also build locally:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

---

## Screenshots

<img src="img/img_tts_engine_select.png" width="25%"><img src="img/img_backend_settings.png" width="25%">

---

## Supported Audio Formats

| Format | Status |
|--------|--------|
| `wav` | ✅ Parsed natively (16-bit PCM & 32-bit float) |
| `pcm` | ✅ Streamed raw (24 kHz mono assumed) |
| `mp3` | ✅ Decoded via MediaCodec |
| `ogg` / `opus` | ✅ Decoded via MediaCodec |
| `aac` / `m4a` | ✅ Decoded via MediaCodec |
| `flac` | ✅ Decoded via MediaCodec |

The container format is sniffed from the response's magic bytes, so a backend that returns MP3 when you asked for WAV still plays correctly.

---

## Future Work / TODO

* Streamed (chunked) playback while downloading.
* More sophisticated language/voice mapping.
