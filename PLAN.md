# LowBudgetVoiceRecognitionInput — Implementation Plan

On-device speech-to-text Android IME. **Four languages:** zh-Hant-TW, zh-Hans-CN, en-US, en-GB.

> **Scope update 2026-04-18** — supersedes the Gemma 4 E2B plan below:
> - Dropped Japanese and Korean. IME now targets 4 languages.
> - Pivoting from **Gemma 4 E2B (raw onnxruntime-android)** to
>   **MediaTek-Research/Breeze-ASR-25** (Whisper-large-v2 fine-tune, native
>   Traditional Chinese, int8 ~1.78 GB) via **sherpa-onnx**.
> - Gemma got end-to-end transcription working on-device ("Hallo"/"あの"
>   for 5 s of audio in ~47 s wall time) but is 9.4× realtime on a 5.3 GB
>   phone — unusable as an IME. Breeze-ASR-25 is purpose-built for ASR,
>   smaller, faster, and outputs Traditional Chinese natively.
> - The design/UX decisions below (push-to-talk, manual language switch,
>   download on first launch, minSdk/RAM gates) still apply. The parts
>   that are Gemma-specific (per-layer inputs, KV-sharing, chat template,
>   custom tokenizer) are being replaced by sherpa-onnx's offline Whisper
>   runtime — a high-level "PCM in, text out" API.
>
> See memory files `project_language_scope_reduction.md` and
> `project_pivot_to_breeze_asr.md` for the full rationale. The Gemma
> text below is preserved as historical record of how we got here.

---

## 1. Confirmed product decisions

| Decision | Choice |
|---|---|
| Recognition trigger | Push-to-talk (hold to record, release to transcribe) |
| Language switch | Manual cycle button in the IME bar |
| Model delivery | Download ~2–3 GB ONNX bundle on first launch |
| Audio cap per utterance | 30 s (Gemma audio encoder limit) |

---

## 2. Architecture overview

```
┌──────────────────────────────────────────────────────────────┐
│ Settings activity (Compose)                                  │
│  - First-run model download + integrity check                │
│  - Storage / RAM advisory                                    │
│  - Default language, mic gain, debug logging                 │
└──────────────────────────────────────────────────────────────┘
              │ (DataStore preferences, file in filesDir)
              ▼
┌──────────────────────────────────────────────────────────────┐
│ VoiceImeService : InputMethodService                         │
│  ┌──────────────┐   ┌──────────────────┐  ┌───────────────┐  │
│  │ Keyboard view│──▶│ AudioCapture     │─▶│ AsrEngine     │  │
│  │ (Compose IME)│   │ (AudioRecord 16k)│  │ (ONNX Runtime)│  │
│  └──────────────┘   └──────────────────┘  └───────────────┘  │
│         ▲                                        │           │
│         └────────────── transcript ──────────────┘           │
│         ▼                                                    │
│  currentInputConnection.commitText(...)                      │
└──────────────────────────────────────────────────────────────┘
```

Key threading model:
- UI on main thread.
- Audio capture on a dedicated `AudioRecord` reader thread, posting 20 ms PCM frames into a ring buffer.
- Inference on a single-threaded coroutine dispatcher (`Dispatchers.Default.limitedParallelism(1)`); the model session is **not** thread-safe.

---

## 3. Module / package layout

Stay in the existing single `app` module to start. Sub-packages under `org.ghostsinthelab.app.lowbudgetvoicerecognitioninput`:

```
.ime          VoiceImeService, KeyboardView, IME bar UI
.audio        AudioCapture, RingBuffer, Resampler, VAD (later)
.asr          AsrEngine, GemmaSession, AudioPreprocessor (mel/log-mel),
              Tokenizer wrapper, prompt templates per language
.model        ModelDownloader, ModelStorage, IntegrityVerifier (sha256),
              ModelManifest
.settings     SettingsActivity, SettingsScreen (Compose), prefs DataStore
.util         Logging, Result types
```

Promote to multi-module (`:app`, `:asr`, `:model`) only if/when build times demand it.

---

## 4. Dependencies to add (`gradle/libs.versions.toml` + `app/build.gradle.kts`)

| Lib | Purpose | Notes |
|---|---|---|
| `com.microsoft.onnxruntime:onnxruntime-android` | ONNX inference | Use the latest 1.x; enable NNAPI EP, fall back to CPU |
| `com.microsoft.onnxruntime:onnxruntime-extensions-android` | Audio decoding ops if used | Optional, only if Gemma's preprocessor graph needs it |
| `androidx.datastore:datastore-preferences` | Settings persistence | Replaces SharedPreferences |
| `androidx.work:work-runtime-ktx` | Resumable model download | Foreground worker with progress notification |
| `com.squareup.okhttp3:okhttp` | HTTP client for HF download | Better range/resume support than HttpURLConnection |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Concurrency | Already transitively present, declare explicitly |

No QNN/NPU EP in v1 — adds vendor-specific build steps. Revisit after CPU/NNAPI baseline works.

---

## 5. Manifest changes (`app/src/main/AndroidManifest.xml`)

- Permissions: `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` (for download worker), `POST_NOTIFICATIONS` (Android 13+).
- Register `VoiceImeService` with `BIND_INPUT_METHOD` permission and an `<intent-filter>` for `android.view.InputMethod`, plus a `<meta-data>` pointing to `res/xml/method.xml`.
- Add `res/xml/method.xml` declaring six subtypes (one per language) so the system IME picker shows them.

---

## 6. Speech pipeline detail

1. **Capture** — `AudioRecord`, 16 kHz mono PCM 16-bit, frame size 320 samples (20 ms). Hard-stop at 30 s; show a countdown on the IME bar from 25 s onward.
2. **Preprocess** — Convert PCM → log-mel spectrogram matching Gemma's audio encoder spec (verify shape from the ONNX `inputs` metadata; the HF repo's `preprocessor_config.json` is authoritative). Likely 80- or 128-bin log-mel, 25 ms window, 10 ms hop.
3. **Prompt** — Build a chat-formatted prompt per the active subtype, e.g.:
   - zh-Hant-TW: `Transcribe the following audio in Traditional Chinese (Taiwan).`
   - en-US: `Transcribe the following audio in English (US).`
   - …interleave the audio token placeholder per Gemma's multimodal template.
4. **Inference** — Run encoder + decoder ONNX sessions; greedy or short beam (beam=1 to start).
5. **Detokenize** → commit text via `InputConnection.commitText`.

Audio preprocessing is the riskiest unknown — confirm whether `onnx-community/gemma-4-E2B-it-ONNX` exposes a preprocessor graph or expects host-side mel computation. If host-side, we need a Kotlin/Native FFT (e.g., port a small log-mel implementation; do **not** add a heavyweight DSP lib).

---

## 7. Model download & storage

- Files land in `context.filesDir/models/gemma-4-e2b-it-onnx/` (app-private, no extra permission).
- Manifest JSON in `assets/` lists each file + sha256 + byte size; the downloader streams from `https://huggingface.co/onnx-community/gemma-4-E2B-it-ONNX/resolve/main/<file>`, verifies hash, and atomically renames into place.
- Foreground service shows progress; resumable via HTTP `Range`. WorkManager handles retries and Wi-Fi-only constraint (configurable).
- Pre-flight checks before download starts: free disk ≥ 4 GB, total RAM ≥ 8 GB (warn at 8 GB, hard-block below 6 GB). Model alone needs ~3 GB resident; headroom is for OS + foreground app + IME.
- `minSdk = 31` (Android 12) is the **current dev-iteration value** (lowered from 35 on 2026-04-18 so existing pre-Android-15 test devices can install). Android has no `minRam` manifest attribute, so RAM enforcement happens at runtime in the pre-flight check above. Before first public release, reconsider raising minSdk back to 33–35 and configure Play Console **device catalog → RAM ≥ 8 GB** as the true hardware floor.

---

## 8. IME UI (Compose)

- Single keyboard view: large circular push-to-talk button, language chip on the left, backspace + return on the right, status text ("Tap and hold to talk" → "Listening…" → "Transcribing…").
- Language chip cycles through the six subtypes; long-press opens a list.
- During transcription, dim the button and show a determinate ring (audio length played back as progress).
- Errors (no model, mic denied, inference failed) inline on the bar with a single tap-to-resolve action (open settings / re-request permission / retry).

Mic permission inside an IME is the classic gotcha: `InputMethodService` cannot request runtime permissions directly. First-launch flow must route the user through `SettingsActivity` to grant `RECORD_AUDIO` before the IME is usable; the IME bar should detect the missing permission and deep-link back into settings.

---

## 9. Implementation milestones

| # | Milestone | Exit criterion |
|---|---|---|
| 1 | Project plumbing: deps, manifest, empty `VoiceImeService`, settings activity | App installs; IME shows up in system picker; selecting it shows a placeholder bar |
| 2 | Audio capture + waveform display | Hold button → see live amplitude meter; release → see PCM length printed |
| 3 | Model downloader + integrity check + storage UI | First-launch flow downloads bundle, verifies sha256, shows used/free space |
| 4 | ONNX session bring-up (no IME) — standalone debug activity that loads Gemma, runs a canned 5-second WAV, prints transcript | Transcription returns non-empty text in en-US for a known sample |
| 5 | Wire AsrEngine into IME, single language (en-US) | End-to-end push-to-talk transcription commits text into a real EditText |
| 6 | Multi-language: prompts + subtypes + IME bar language chip | All six languages produce reasonable transcripts on hand-picked samples |
| 7 | Polish: error states, low-RAM guard, telemetry-free logging toggle, ProGuard rules for ORT | Internal alpha build |

Milestone 4 is the highest-risk; if Gemma's audio path on ORT-Android proves unworkable (e.g. unsupported ops), we fall back to `whisper.cpp` per language. Decide at end of M4, not earlier.

---

## 10. Open questions / risks

1. **Audio preprocessor format** — needs verification against the HF repo's config files before M4.
2. **ONNX op coverage on Android** — Gemma 4's audio encoder may use ops NNAPI doesn't accelerate; CPU-only could be too slow on mid-range phones (target: < 5 s for a 10 s utterance).
3. **Memory pressure** — Q4 ~3 GB resident is rough on 6 GB devices; consider mmap'd weights via ORT's `session_options.add_session_config_entry("session.use_ort_model_bytes_directly", "1")` or external data files.
4. **Tokenizer** — does the ONNX bundle ship a tokenizer.json compatible with HuggingFace tokenizers Java port, or do we need to embed SentencePiece directly?
5. **Subtype switching** — Android's IME subtype API is finicky; verify behavior on Android 14/15 emulators early.
6. **Battery / thermal** — sustained inference will warm the device; consider thermal-throttling-aware backoff later.

---

## 11. Out of scope (for v1)

- Streaming / continuous dictation
- Custom vocabulary / personalization
- Punctuation re-formatting beyond what Gemma emits
- Offline model updates / delta downloads
- Cloud fallback
