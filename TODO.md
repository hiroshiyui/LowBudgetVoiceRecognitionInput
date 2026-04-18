# TODO

Tracks milestones from PLAN.md. M1–M3 + M4a complete; M4b is next.

## M1 — Project plumbing  ✅ done
- [x] Add `RECORD_AUDIO` + `INTERNET` permissions in AndroidManifest
- [x] Register `VoiceImeService` with `BIND_INPUT_METHOD` and `android.view.im` meta-data
- [x] Create `res/xml/method.xml` with 6 IME subtypes (zh-Hant-TW, zh-Hans-CN, en-US, en-GB, ja, ko)
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

## M4b — Tokenizer + decoder + end-to-end transcription  🚧 next
- [ ] Pick tokenizer path: port tokenizer.json BPE loader to Kotlin / pre-convert
      with ORT Extensions / third-party JVM tokenizer (investigate first)
- [ ] `Tokenizer` wrapper — encode text prompt, decode token IDs → string
- [ ] `EmbedTokensSession` — load `embed_tokens_q4f16.onnx`, produce `inputs_embeds`
- [ ] Scatter op: replace positions where `input_ids == 258881` (audio_token_id)
      with audio_features (already in 1536-dim hidden space — no projection needed)
- [ ] `DecoderSession` — load `decoder_model_merged_q4f16.onnx`, manage KV cache
      across 35 layers (sliding + full attention)
- [ ] ASR prompt template (from `chat_template.jinja`): 
      "Transcribe the following speech segment in English into English text..."
      with `<boa>` `<audio>×125` `<eoa>` placeholders
- [ ] Greedy decode loop with EOS stop (token IDs 1 or 106)
- [ ] Wire into AsrDebugActivity: record → preprocess → encode → tokenize →
      embed → scatter → decode → detokenize → print transcript
- [ ] Measure end-to-end latency on device (expect 5-10 s for 5 s audio)
- [ ] **Decision gate:** if transcription is garbage, the mel recipe is first
      suspect (Hamming vs Povey, HTK vs Slaney, log vs log10); if latency is
      unworkable, fall back to per-language Whisper

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
