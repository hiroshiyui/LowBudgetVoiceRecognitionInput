# TODO

**Status 2026-04-18 (paused):** the project reached the end of its current iteration loop. Both ASR stacks (Gemma 4 E2B, Breeze-ASR-25) are wired end-to-end in code; neither ships because the test device (5.3 GB RAM) is too small for Breeze (LMKD-killed on first decode step) and Gemma is 9.4× realtime (47 s for 5 s audio). Realistic paths forward are listed in `README.md`. The IME service, audio capture, download infrastructure, and debug tooling are all functional — the only unsolved piece is "an ASR engine that fits this device **and** outputs Traditional Chinese natively". Leaving it here until the user has time and/or a beefier device.

**Scope change 2026-04-18:** dropped Japanese and Korean; IME targets 4 languages (zh-Hant-TW, zh-Hans-CN, en-US, en-GB).

Tracks milestones from PLAN.md.

## M1 — Project plumbing  ✅ done
- [x] Add `RECORD_AUDIO` + `INTERNET` permissions in AndroidManifest
- [x] Register `VoiceImeService` with `BIND_INPUT_METHOD` and `android.view.im` meta-data
- [x] Create `res/xml/method.xml` with 4 IME subtypes (zh-Hant-TW, zh-Hans-CN, en-US, en-GB) — was 6, dropped ja/ko 2026-04-18
- [x] `VoiceImeService` skeleton with placeholder bar (200 dp)
- [x] Add IME label, subtype labels, and settings strings
- [x] Rewrite `MainActivity` as a 3-step Compose setup screen
- [x] Remove unused template files (`ic_home`, `ic_favorite`, `ic_account_box`, `AppDestinations`)
- [x] Raise `minSdk` to 35 (≥ 8 GB RAM hardware floor)

## M2 — Audio capture + waveform display  ✅ done
- [x] Make `VoiceImeService` lifecycle/savedstate/viewmodel-aware so ComposeView can host UI
- [x] `AudioCapture`: `AudioRecord` wrapper, 16 kHz mono PCM 16-bit, 20 ms frames, 30 s cap
- [x] Expose `state` / `amplitude` / `samplesRead` StateFlows
- [x] Compose `ImeUi`: push-to-talk button, live amplitude meter, status text
- [x] Mic-permission gate inside the IME (deep-link to app details — IMEs can't request runtime perms)
- [x] Print captured PCM length on release

## M3 — Model downloader + integrity check + storage UI  ✅ done
- [x] Verify HF file list, pick audio-only q4f16 subset (~3.08 GB)
- [x] Add OkHttp + WorkManager + DataStore deps
- [x] Add `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` permissions
- [x] Bundle `assets/model_manifest.json` with SHA-256s for large files
- [x] `ModelManifest`, `ModelStorage`, `Preflight`, `ModelDownloader`
- [x] `DownloadWorker` (CoroutineWorker, foreground, drives downloader for all entries)
- [x] `ModelDownloadController` (UI-facing facade — enqueue / cancel / observe state)
- [x] Pre-flight: RAM ≥ 6 GB hard / 8 GB warn; free disk ≥ 4 GB
- [x] Replace settings step 3 with real download UI (progress, free disk, RAM, start / cancel / delete)
- [x] Build and verify M3 compiles

## M4a — ONNX infra + audio encoder validation  ✅ done
- [x] Add `com.microsoft.onnxruntime:onnxruntime-android 1.20.0` dep
- [x] Add ProGuard/R8 keep rules for ORT
- [x] `AudioPreprocessor` — 128-bin log-mel, HTK scale, Hamming window, radix-2 FFT (no DSP deps)
- [x] `AudioEncoderSession` — ORT wrapper loading `audio_encoder_q4f16.onnx` + sidecar `.onnx_data`
- [x] `AsrDebugActivity` — standalone activity: record → preprocess → encode → print output
- [x] Validated on-device (5.3 GB RAM): preprocess 313 ms, encoder load 1.36 s,
      encoder forward 3.4 s for 5 s audio, output shape `[125, 1536]`, bool mask
      (not int64 as originally guessed)

## M4b — Tokenizer + decoder + end-to-end transcription  ✅ done
- [x] Pure-Kotlin `GemmaTokenizer` — BPE with byte-fallback, SentencePiece metaspace
      (no external deps; DJL / ORT-Extensions / onnxruntime-genai all failed to
      produce an Android arm64 tokenizer)
- [x] `EmbedTokensSession` — returns both `inputs_embeds` (1536) AND
      `per_layer_inputs` (35 × 256); both are required by the decoder
- [x] `AudioScatter` — overwrites embedding rows at positions where
      `input_ids == 258881` with audio_features rows
- [x] `DecoderSession` — merged prefill + decode with KV cache management across
      15 KV-cached layers (20 layers share), mixed head_dim (256 sliding / 512
      full for layers 4, 9, 14); ORT 1.24.3 for the newer `GatherBlockQuantized`
      and `GroupQueryAttention` ops
- [x] `Fp16` — manual binary16 → float32 conversion (Java 20+'s `Float.float16ToFloat`
      not available on our minSdk)
- [x] `AsrPrompt` — hardcoded chat-template prompt for all six languages,
      with `<|audio|>` repeated numAudioTokens times
- [x] Greedy decode loop with EOS stop (ids 1 or 106)
- [x] Full pipeline wired in `AsrDebugActivity` with top-5 logit diagnostics,
      prompt-tail decoding, and auto-save to `getExternalFilesDir()/logs/`
- [x] **Validated on-device** (5.3 GB RAM): 5 s audio → transcript in 47 s
      (prefill 25 s / 163 prompt tokens, decode 5.4 s/token); correctness
      proven on text-only continuation test ("capital of France is" → "Paris.")
- [x] Tokenizer bug fixed: metaspace `▁` was being prepended between every
      special token, corrupting the chat-template structure — now only emitted
      at the very start of the input
- [x] RAM mitigation: embed_tokens reopened briefly per decode step so
      embed_tokens (1.5 GB) + decoder (1.4 GB) aren't held together on
      low-RAM devices — prevented kswapd thrashing

## M4 follow-ups (mostly superseded by the Breeze pivot)
- [x] Try NNAPI execution provider on the decoder — attempted 2026-04-18,
      +70% wall time on this device due to partitioning around custom
      com.microsoft ops; disabled by default, scaffolding kept
- [ ] ~~Verify mel preprocessing matches Gemma's reference~~ — moot after pivot;
      sherpa-onnx does Whisper mel internally
- [ ] ~~Test transcription quality on ja/ko~~ — dropped from scope 2026-04-18

## M4-bridge — Breeze-ASR-25 pivot  ⏸ blocked on device RAM
- [x] Sherpa-onnx evaluated and skipped — the Breeze ONNX loads fine on the
      `onnxruntime-android` we already ship; sherpa-onnx adds a 54 MB AAR
      (not on Maven Central) + a second ORT copy for no extra capability.
- [x] `assets/breeze_model_manifest.json` — int8 subset: encoder 766 MB,
      decoder 1.01 GB, tokens.txt 798 KB; SHA-256s from HF LFS `oid`.
- [x] `ModelBundle` enum plumbs Gemma + Breeze side by side (manifest asset,
      storage subdir, WorkManager work name, human label).
- [x] `WhisperPreprocessor` — 80-bin Slaney log-mel; 512-point FFT approx
      of Whisper's 400 (zero-pad + 257-bin filterbank). Mel-frame count is
      configurable — `BreezeAsrRunner` uses 1000 frames (10 s window) to
      shrink cross K/V from 490 MB → 160 MB.
- [x] `WhisperTokenizer` — sherpa-onnx tokens.txt (space-separated,
      base64-encoded pieces). `decodeBase64` verified on-device. Whisper
      specials aren't in the file; IDs hardcoded from openai/whisper
      canonical order.
- [x] `WhisperEncoder` — ORT wrapper. Input `mel` f32 [1,80,N]; outputs
      `n_layer_cross_k` / `n_layer_cross_v` f32 [32,1,N/2,1280].
- [x] `WhisperDecoder` — ORT wrapper with fixed 448-slot self-KV cache and
      `offset` scalar. Step wraps the session Result so self-KV tensors
      survive across calls without a clone.
- [x] `BreezeAsrRunner` — preprocess → encode → close encoder → prefill
      `<|sot|><|lang|><|transcribe|><|notimestamps|>` → greedy decode until
      `<|endoftext|>` or offset 448 → base64 detokenise.
- [x] `AsrDebugActivity` Breeze buttons (Test tok, Inspect enc, Inspect dec,
      Run pipeline); per-phase `Log.d(TAG="BreezeAsr", …)` breadcrumbs for
      silent LMKD kills.
- [ ] ~~End-to-end transcript on the test device~~ — BLOCKED. Pipeline
      reaches `prefill firstPredicted=15947` then the process is
      LMKD-killed during the first decoder.forward step. Peak memory
      (decoder 961 MB + cross K/V 160 MB + 2× self-KV 290 MB + ORT
      workspace + Java heap 300 MB + OS) exceeds what the 5.3 GB device
      has free. No further code-level mitigation will fix this; the model
      simply needs more RAM.

## What's left to unblock the IME
1. **Validate on an 8+ GB device** — codebase is ready; nothing new to write
   if Breeze works on bigger hardware.
2. **Swap in Whisper-small + OpenCC** if Breeze can't be carried forward.
   The `ModelBundle` plumbing already supports multiple bundles side-by-side.
3. **Hybrid: Whisper-small baseline + Breeze premium** — runtime picks
   based on `Preflight.totalRamBytes`; same download flow, two manifests.

## M5 — Wire AsrEngine into IME (en-US only)  ⏸ pending
- [ ] `AsrEngine` interface; `GemmaSession` impl using ORT
- [ ] Single-threaded dispatcher for ORT session (sessions are not thread-safe)
- [ ] On PTT release: PCM → preprocess → encode → decode → text
- [ ] `InputConnection.commitText` from the IME
- [ ] Loading / transcribing / error states on the IME bar

## M6 — Multi-language: prompts + subtypes + IME bar chip  ⏸ pending
- [ ] Per-language prompt templates (zh-Hant-TW emphasizes Traditional output)
- [ ] Subtype switching wiring (`onCurrentInputMethodSubtypeChanged`)
- [ ] Language chip on the IME bar (cycle six subtypes)
- [ ] Persist last-used language in DataStore
- [ ] Sanity-check transcripts for each language on hand-picked samples

## M7 — Polish  ⏸ pending
- [ ] Error states inline on IME bar with single tap-to-resolve
- [ ] Low-RAM guard at runtime (refuse to load if free RAM is too tight)
- [ ] Logging toggle (off by default — never log audio or transcripts)
- [ ] ProGuard / R8 keep rules verified for release variant
- [ ] Unload model on `onFinishInputView` to free RAM (cold-start trade-off)
- [ ] Internal alpha build

## Cross-cutting / open questions (from PLAN.md §10)
- [ ] Verify audio preprocessor format against HF `preprocessor_config.json` (M4)
- [ ] Measure ONNX op coverage on NNAPI for the Gemma audio encoder (M4)
- [ ] Decide on mmap'd weights via ORT external-data files (M4 / M5)
- [ ] Confirm tokenizer compatibility (HF tokenizers Java port vs. SentencePiece) (M4)
- [ ] Test subtype switching on Android 14 / 15 emulators (M6)
- [ ] Battery / thermal-throttling-aware backoff (deferred)

## Download-path follow-ups (M3 trade-offs to revisit if they bite)
- [ ] HTTP Range resume — today a failed file restarts from scratch
- [ ] Byte-granular progress persistence across process death
- [ ] Consider mirroring the model to a second host as fallback

## Out of scope for v1 (from PLAN.md §11)
- Streaming / continuous dictation
- Custom vocabulary / personalization
- Punctuation re-formatting beyond what Gemma emits
- Offline model updates / delta downloads
- Cloud fallback
