# TODO

Tracks milestones from PLAN.md. M1–M3 complete; M4 is next.

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

## M4 — Standalone ONNX inference prototype  🚧 next
- [ ] Add `com.microsoft.onnxruntime:onnxruntime-android` dep (pick a stable 1.x)
- [ ] Add ProGuard/R8 keep rules for ORT
- [ ] Separate debug activity that loads Gemma 4 E2B ONNX (not yet wired into the IME)
- [ ] Implement audio preprocessor (log-mel spectrogram per HF `preprocessor_config.json`)
- [ ] Tokenizer wrapper — verify shipped `tokenizer.json` is loadable on Android
- [ ] Build chat-template prompt with audio token interleaving (from `chat_template.jinja`)
- [ ] Run encoder + decoder on a known WAV; print transcript
- [ ] **Decision gate:** if unworkable, fall back to per-language Whisper (per PLAN.md §10)

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
