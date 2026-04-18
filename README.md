# Low Budget Voice Recognition Input

An Android Input Method Editor that transcribes speech entirely on-device. Target languages: zh-Hant-TW, zh-Hans-CN, en-US, en-GB (Japanese and Korean dropped on 2026-04-18).

## Status

**Paused 2026-04-18** after reaching a hardware wall on the test device. Two ASR engines are wired end-to-end in code; neither is practical for shipping yet.

| Engine | Code state | On-device result |
|---|---|---|
| **Gemma 4 E2B ONNX** (onnx-community/gemma-4-E2B-it-ONNX, q4f16) | Full pipeline committed, `ASR debug → Run pipeline` | ~47 s wall time for 5 s audio; produced "Hallo" / "あの…" transcripts. Correct but 9.4× realtime — not usable as an IME. |
| **Breeze-ASR-25** (MediaTek-Research/Breeze-ASR-25-onnx-250806, int8) | Full pipeline committed, `ASR debug → Run Breeze pipeline` | Pipeline completes preprocess → encoder → prefill successfully (firstPredictedId=15947). Process gets LMKD-killed on the first greedy decode step. |

## Why the stall

Breeze-ASR-25 is Whisper-large-v2 fine-tuned for Taiwan Mandarin + zh/en code-switching — the only model I found that outputs Traditional Chinese natively without needing OpenCC post-conversion. Its int8 ONNX is 1.69 GB on disk. At peak during the first decode step the app holds:

- decoder weights ~961 MB
- encoder cross-attention K/V ~160 MB (10 s window; 490 MB at Whisper's default 30 s)
- two decoder self-KV caches briefly alive (old + new) ~290 MB
- ORT inference workspace ~several hundred MB
- Java heap ~300 MB

On the 5.3 GB RAM test device this crosses the Low-Memory-Killer threshold. Mitigations attempted, none sufficient on this hardware:

- minSdk lowered 35 → 31 for dev install
- ONNX Runtime upgraded 1.20.0 → 1.24.3 (for GatherBlockQuantized + 11-input GroupQueryAttention)
- Download throttle (500 ms between WorkManager progress emits) — fixes notification rate-limit kill during model download
- foreground_service_type=dataSync manifest merge for Android 14+
- IOException retry in DownloadWorker (HF CDN "unexpected end of stream" recovery)
- NNAPI attempted and reverted — net +70% wall time due to partitioning around `com.microsoft`-domain custom ops
- ORT `setMemoryPatternOptimization(false)` + `setCPUArenaAllocator(false)`
- Whisper encoder window trimmed from 3000 → 1000 mel frames (saves ~330 MB on cross K/V)
- Decoder session reopened per-token during Gemma generation to avoid holding both embed_tokens and decoder simultaneously

## Realistic options to revisit this

1. **Test on a ≥ 8 GB RAM device.** The code is correct up through prefill, and peak memory fits in an 8 GB phone. The runtime preflight (`app/src/main/java/.../model/Preflight.kt`) hard-blocks < 6 GB and warns < 8 GB for this reason.
2. **Accept Whisper-small + OpenCC.** ~240 MB int8; works on this device. Transcription is in Simplified; post-convert with OpenCC (~2 MB dictionary). Previously rejected in favour of Breeze's native Traditional output.
3. **Ship Whisper-small as the baseline, make Breeze an opt-in "premium" download for devices that meet a RAM check.** Two-model UX, complex but covers both cases.

## What's built

Primary code lives under `app/src/main/java/org/ghostsinthelab/app/lowbudgetvoicerecognitioninput/`:

- **`ime/`** — `VoiceImeService` (InputMethodService + Lifecycle/ViewModel/SavedState owners so Compose can host the IME UI), `ImeUi` (push-to-talk Compose panel, mic permission gate, 200 dp bar).
- **`audio/`** — `AudioCapture` (coroutine-wrapped `AudioRecord`, 16 kHz mono PCM 16-bit, 20 ms frames, 30 s cap, exposes `state` / `amplitude` / `samplesRead` `StateFlow`s).
- **`asr/`** — two ASR stacks side-by-side:
  - Gemma stack: `AudioPreprocessor` (128-bin HTK log-mel), `AudioEncoderSession`, `EmbedTokensSession`, `AudioScatter`, `GemmaTokenizer` (pure-Kotlin BPE), `DecoderSession` (35-layer mixed-KV merged decoder), `Fp16`, `AsrPrompt`, `AsrSessionOptions`.
  - Whisper / Breeze stack: `WhisperPreprocessor` (80-bin Slaney log-mel; 512-point FFT approximation of Whisper's 400), `WhisperTokenizer` (sherpa-onnx tokens.txt, base64-encoded pieces, hardcoded Whisper-large-v2 special IDs), `WhisperEncoder`, `WhisperDecoder` (32-layer, fixed 448-slot self-KV cache, cross-K/V fed from encoder), `BreezeAsrRunner`.
- **`model/`** — `ModelBundle` (Gemma / Breeze), `ModelManifest`, `ModelStorage`, `ModelDownloader` (OkHttp streamed + SHA-256 verify + atomic rename), `DownloadWorker` (foreground CoroutineWorker, retries on IOException), `ModelDownloadController`, `Preflight` (RAM + disk).
- **`MainActivity`** — three-step setup screen (enable IME, pick IME, download model) plus a Debug card linking to `AsrDebugActivity`.
- **`AsrDebugActivity`** — buttons for isolated tests: tokenizer, encoder inspection, decoder inspection, text-only decoder, full Gemma pipeline, full Breeze pipeline. Logs save to `context.getExternalFilesDir("logs")/log_<usage>.txt` for copy-paste off device.

## Milestones

`PLAN.md` has the original 7-milestone plan. `TODO.md` tracks current status. M1–M4a shipped end-to-end for Gemma; M4b (decoder) works correctly in code but is latency-unusable; the Breeze pivot reached "prefill produces a prediction" before hitting the RAM wall.

## See also

- `PLAN.md` — architectural plan, design decisions, open questions
- `TODO.md` — milestone checklist
- `.claude/skills/` — project-scoped slash-commands (code review, commit/push, docs, release engineering)
