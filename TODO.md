# TODO

**Scope change 2026-04-18:** dropped Japanese and Korean; IME now targets 4 languages (zh-Hant-TW, zh-Hans-CN, en-US, en-GB). Pivoting the ASR engine from Gemma 4 E2B (raw onnxruntime-android) to **MediaTek-Research/Breeze-ASR-25** via **sherpa-onnx** for native Traditional Chinese output + near-realtime latency. Gemma code stays working until Breeze is validated end-to-end.

Tracks milestones from PLAN.md. M1–M4 complete end-to-end on Gemma; M4-bridge (Breeze pivot) is next before M5.

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

## M4-bridge — Pivot to Breeze-ASR-25 via sherpa-onnx  🚧 next
- [ ] Download `sherpa-onnx-<latest>.aar` from GitHub Releases, place in
      `app/libs/`, wire via `implementation(files("libs/..."))`
- [ ] Add `abiFilters = listOf("arm64-v8a")` to trim the 54 MB AAR
- [ ] Drop `onnxruntime-android` dep from build.gradle.kts (sherpa-onnx
      bundles its own ORT) — or keep until Gemma code is gone
- [ ] Update `assets/model_manifest.json` to point to
      `MediaTek-Research/Breeze-ASR-25-onnx-250806` int8 variant:
      encoder (766 MB) + decoder (1.01 GB) + tokens.txt (~817 kB) ≈ 1.78 GB
- [ ] Compute SHA-256 of each file at build time (HF LFS pointers expose
      `oid sha256:...` via the `raw/main/` URL) and bake into the manifest
- [ ] Write a `BreezeAsrSession` Kotlin wrapper that uses `OfflineRecognizer`
      + `OfflineStream` API: accepts ShortArray PCM, returns transcript
- [ ] Wire per-IME-subtype `language` code: "zh" for both zh subtypes,
      "en" for both en subtypes (OfflineWhisperModelConfig.language)
- [ ] Add a "Test Breeze" button to AsrDebugActivity for on-device validation
- [ ] Measure latency — expected near-realtime on a 5.3 GB device given
      int8 quantization and sherpa-onnx's optimized Whisper path
- [ ] Once validated end-to-end, delete `asr/` Gemma files: AudioPreprocessor,
      AudioEncoderSession, EmbedTokensSession, DecoderSession, AudioScatter,
      Fp16, GemmaTokenizer, AsrPrompt, AsrSessionOptions (keep AudioCapture)

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
