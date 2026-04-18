---
name: docs-engineering
description: Writing/updating project documentation (README, PLAN, PRIVACY-POLICY, NOTICES, changelogs). Use when the user asks to update docs, write changelogs, or modify store listings.
argument-hint: task description
---

# Document Engineering

You are performing documentation tasks for **Low Budget Voice Recognition Input**.

## Project Documentation Files

Current docs (as of milestone 2):

| File | Status | Purpose |
|------|--------|---------|
| `PLAN.md` | ✅ exists | Implementation plan, milestones, architectural decisions |

Forthcoming (write only when the user asks for them):

| File | Purpose |
|------|---------|
| `README.md` | Project overview, build instructions, usage |
| `PRIVACY-POLICY.md` | Required — this app captures microphone audio and downloads a model |
| `NOTICES.md` | Third-party license notices (Gemma terms, ONNX Runtime, etc.) |
| `CLAUDE.md` | Guidance for Claude Code on this codebase |

When updating `NOTICES.md`, derive the dependency list from `gradle/libs.versions.toml` and `app/build.gradle.kts`. Include:
- ONNX Runtime Android (Microsoft, MIT)
- Gemma 4 E2B model weights (Google, Gemma Terms of Use — note this is **not** OSI-approved)
- AndroidX Compose / Material3 / Lifecycle / etc. (Apache 2.0)
- Any future deps (OkHttp, WorkManager, DataStore)

## Bilingual Convention

This project supports six recognition languages, but the documentation itself is maintained in **two**:

- **English (en-US)** — primary
- **Traditional Chinese (zh-Hant-TW)** — secondary, since the project is developed in Taiwan and zh-Hant-TW is one of the headline IME languages

Other supported recognition languages (zh-Hans-CN, en-GB, ja, ko) do **not** require their own docs.

For files like `README.md`, use interleaved bilingual sections (English heading/paragraph followed by zh-Hant-TW equivalent) — same pattern as the user's other Android projects.

## Style Guide

- Clear, approachable prose. No marketing fluff.
- For zh-Hant-TW text, use Traditional Chinese characters only. Do **not** use Simplified.
- When referring to the model, write "Gemma 4 E2B" (not "gemma4", "Gemma-4-E2B-it", etc.).
- When referring to the recognition runtime, write "ONNX Runtime" — not "ONNX" alone, and not "onnxruntime".
- When referring to the IME's six languages, use:
  - 中文 (台灣) / Mandarin (Taiwan)
  - 中文 (中国大陆) / Mandarin (Mainland China)
  - English (US)
  - English (UK)
  - 日本語 / Japanese
  - 한국어 / Korean
- Be honest about hardware requirements — the app needs ≥8 GB RAM and ~3 GB free storage. Do not soft-pedal this in the README or store listing.
- **Respect external resource providers' efforts.** When describing limitations in upstream projects, dependencies, or third-party services (Hugging Face, Google, Microsoft, model authors), use softened wording: prefer "issue", "behavior", or "limitation" over "bug"; prefer "resolved" or "addressed" over "fixed". Frame workarounds as collaborative rather than corrective. This applies to both en-US and zh-Hant-TW (e.g. use 「問題」 rather than 「錯誤」 or 「bug」 when referring to upstream).

## Privacy Policy Notes

When `PRIVACY-POLICY.md` is written, it must accurately disclose:

- **Microphone audio** is captured only while the user holds the push-to-talk button. Audio is processed entirely on-device. Audio is **never** sent to any network endpoint.
- **Model files** (~2–3 GB) are downloaded from Hugging Face on first launch. The app makes one outbound HTTPS connection per file to `huggingface.co`. No telemetry, no analytics, no crash reporting (unless explicitly added later — and if so, must be opt-in and listed here).
- **Transcribed text** is committed to the input field of whatever app the user is typing in. The IME does not store, log, or transmit transcripts.
- **No personally identifiable information** is collected or transmitted by this app.

If the project later adds opt-in telemetry, crash reporting, or any non-local processing, this document must be updated **before** the feature ships.

## Store Listing / Distribution

The project does **not** currently ship to F-Droid, Play Store, or any other distribution channel. If/when that changes, ask the user which channels and follow the conventions of that channel — do not assume the previous project's F-Droid setup applies here.

## Task: $ARGUMENTS
