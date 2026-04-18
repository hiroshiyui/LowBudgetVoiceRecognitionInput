---
name: code-review-and-security-audit
description: Review code for quality, correctness, and security vulnerabilities. Use when the user asks to review code, audit for security issues, or check for bugs and anti-patterns.
argument-hint: file path, component name, or scope of review
---

# Code Review and Security Audit

You are performing code review and security auditing for **Low Budget Voice Recognition Input** — an on-device Android IME that captures audio and transcribes it via the Gemma 4 E2B ONNX multimodal model.

## Scope

Two complementary concerns:

1. **Code Review** — correctness, readability, maintainability, adherence to project conventions.
2. **Security Audit** — vulnerabilities, unsafe patterns, attack surface specific to an IME that handles microphone input and downloads a 2–3 GB model from the network.

## Review Checklist

### Code Quality

- Null safety: idiomatic Kotlin null-safe operators; flag `!!` unless justified.
- Coroutine correctness: structured concurrency, proper cancellation handling, no leaked `GlobalScope` use, dispatchers chosen correctly (`Dispatchers.IO` for blocking I/O, `Dispatchers.Default` for CPU work, single-threaded dispatcher for the ONNX session).
- Resource management: `AudioRecord`, `OrtSession`, `OrtEnvironment`, `FileChannel`, `OkHttp Response` bodies all released in `finally` or `use {}`.
- Thread/state safety: ONNX Runtime sessions are **not** thread-safe — confirm single-threaded access. `MutableStateFlow`/`StateFlow` reads/writes follow Compose recomposition rules.
- Compose correctness: `remember`/`rememberSaveable` keys, no side effects in composition, `LaunchedEffect`/`DisposableEffect` properly keyed, no leaks of the `InputMethodService` reference into Composables that outlive the IME view.
- Lifecycle: `VoiceImeService` correctly drives `LifecycleRegistry`/`SavedStateRegistryController` across `onCreate` / `onStartInputView` / `onFinishInputView` / `onDestroy`.
- Error handling: no silently swallowed exceptions; `runCatching` only when the failure mode is genuinely benign.
- Dead code, unused imports, redundant logic.

### Code Smells

- Long methods, large classes, duplicated logic, deep nesting, magic numbers, primitive obsession, long parameter lists, mutable shared state.
- Compose composables that mix UI and business logic (e.g. starting `AudioCapture` directly in `ImeUi.kt` is acceptable for the dev skeleton, but flag it once a ViewModel layer exists).

### Refactoring Suggestions

- Extract method / extract class when responsibilities pile up.
- Replace `when`-on-string with sealed types (e.g. language subtype).
- Use idiomatic Kotlin: scope functions, destructuring, extension functions, `buildList`/`buildString`.
- Lifecycle helpers: `lifecycleScope`, `repeatOnLifecycle`, `LifecycleObserver`.
- Reduce coupling between IME / audio / ASR / model layers — they should communicate through narrow interfaces, not concrete classes.
- Improve testability — the audio and ASR layers should be unit-testable without an Android device (no static `Log.d`, no direct `Context` reliance where avoidable).

## Security Concerns Specific to This Project

### IME-Specific (highest priority)

This app is an Input Method Editor — it sees what the user is dictating. Treat all transcribed text and captured audio as **highly sensitive**.

- **No logging of audio or transcripts.** Not to logcat, not to crash reports, not to files. Even debug builds should gate this behind an explicit developer toggle.
- **No transcripts or audio committed to disk** beyond what the user explicitly initiates. The committed `InputConnection` text never persists in our process.
- **Mic capture only while the IME view is shown and the user is actively pressing PTT.** Never run `AudioRecord` in the background, never run while `onFinishInputView` has been called.
- **No network egress of audio or transcripts.** The only allowed network traffic is the model download from Hugging Face.

### Audio Pipeline

- `AudioRecord` released on every code path (cancellation, error, success). Confirm `recorder.stop()` and `recorder.release()` in `finally`.
- 30-second hard cap enforced even if the UI button is held longer.
- Re-check `RECORD_AUDIO` permission on every IME show, not just at process start (user may revoke in Settings while the IME is loaded).
- No PCM data retained in memory after the transcription completes — clear or null out the buffer.

### Model & Network

- Model download URL is `huggingface.co/onnx-community/gemma-4-E2B-it-ONNX` (HTTPS only).
- Verify SHA-256 of every downloaded file against an in-repo manifest before using. Treat hash mismatch as a hard failure — delete the partial file, surface the error.
- Files written to `context.filesDir/models/...` (app-private storage). Never to external/world-readable storage.
- Atomic rename: download to `<file>.partial`, verify, then rename.
- Resumable via HTTP `Range` — verify the server's `ETag`/`Content-Length` matches between resumes.

### ONNX Runtime

- Load weights via mmap / external data files where possible — avoid loading the full 2–3 GB into the Java heap.
- ORT session is single-threaded; guard with a single-threaded dispatcher.
- Set `enable_mem_pattern = false` and `enable_cpu_mem_arena = false` if memory growth is observed.
- Cap `max_new_tokens` to prevent runaway generation.

### General Android

- **Component export**: `VoiceImeService` is exported with `BIND_INPUT_METHOD` permission (correct for IMEs); `MainActivity` is exported as `LAUNCHER`. No other components should be exported.
- **Permissions**: only `RECORD_AUDIO` and `INTERNET` declared; reject any new permission additions without justification.
- **Intent handling**: `MainActivity` doesn't currently consume external intents; if that changes, validate all extras.
- **No `addJavascriptInterface`**, no `WebView`, no dynamic class loading.
- **No hardcoded secrets** — there are none today; reject any future PR that adds one.

### Dependencies

Cross-check `gradle/libs.versions.toml` against current advisories for:
- `androidx.compose.*` (Compose BOM)
- `com.microsoft.onnxruntime:onnxruntime-android` (when added)
- `androidx.work:work-runtime-ktx` (when added)
- `com.squareup.okhttp3:okhttp` (when added)

## Output Format

Report findings using this structure:

### Critical / High
Issues that must be fixed — security vulnerabilities, crashes, data-loss risks, IME confidentiality breaches.

### Medium
Issues that should be fixed — logic bugs, thread-safety concerns, resource leaks, code smell.

### Low / Informational
Suggestions for improvement — style, readability, minor optimizations.

For each finding include:
- **File and line number** (e.g., `audio/AudioCapture.kt:73`)
- **Description** of the issue
- **Impact** — what could go wrong
- **Recommendation** — how to fix it, with code if appropriate

## How to Run

When invoked without arguments, review files changed since the last review or in the current milestone. Without git history available, ask the user which milestone or files to focus on.

When invoked with a specific scope (file, directory, milestone number), focus the review on that area.

For a full audit, systematically review:
1. IME service + Compose UI (`ime/`)
2. Audio capture (`audio/`)
3. ASR / ONNX integration (`asr/` — once it exists)
4. Model download + storage (`model/` — once it exists)
5. Settings activity (`MainActivity.kt`)
6. Manifest + IME subtype config (`AndroidManifest.xml`, `res/xml/method.xml`)
7. Dependencies (`gradle/libs.versions.toml`)

## Task: $ARGUMENTS
