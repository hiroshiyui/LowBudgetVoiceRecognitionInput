---
name: release-engineering
description: Release engineering tasks — version bumping, building release APKs, tagging releases, writing changelogs. Use when the user asks to prepare a release, bump version, tag, or build for distribution.
argument-hint: task description
---

# Release Engineering

You are performing release engineering tasks for **Low Budget Voice Recognition Input**.

## Status: Pre-Release

This project is in active early development (milestone 2 of 7). There is no public release yet, no signing keystore, no distribution channel chosen, and the project is not under git.

If the user asks to "release" or "tag" something today, **stop and confirm** what they actually want — most likely they mean a build for personal testing rather than a public release.

## Version Scheme (current)

From `app/build.gradle.kts`:

- **versionName**: `"1.0"` (currently a placeholder — change to semver `MAJOR.MINOR.PATCH` before any real release)
- **versionCode**: `1` (monotonic integer)

There is no `bumpPatchVersion` task today; bumps are manual edits to `app/build.gradle.kts`.

## Build Commands

```bash
./gradlew :app:assembleDebug       # Debug build → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease     # Release build (currently unsigned, isMinifyEnabled = false)
./gradlew :app:test                # Unit tests
./gradlew :app:connectedAndroidTest # Instrumented tests
./gradlew :app:clean
```

The release variant currently has `isMinifyEnabled = false` and no signing config — it is **not** shippable as-is.

## What Needs to Be Decided Before First Release

Ask the user about each of these before assuming a workflow:

1. **Distribution channel**: Play Store? F-Droid? GitHub Releases only? Sideload only?
2. **Signing**: where the keystore lives, whether GPG-signing the APK is wanted (the user does this on other projects).
3. **Minification**: enable R8/ProGuard for the release variant? ONNX Runtime needs keep rules.
4. **Model bundling vs. download**: PLAN.md commits to download-on-first-launch — confirm this is still the intent.
5. **Hardware filtering**: enable Play Console RAM ≥ 8 GB device-catalog filtering (per `project_design_decisions` memory).
6. **Changelog format**: the user's other Android project uses F-Droid `fastlane/metadata/android/.../changelogs/<versionCode>.txt` — adopt the same? Or keep changelog inline in `README.md` / GitHub Releases?
7. **Tag style**: the user's other project uses lightweight tags `X.Y.Z` (no `v` prefix). Adopt the same?

## Provisional Release Workflow (when the time comes)

These steps mirror the user's existing convention but must be confirmed before use:

1. Ensure all changes are committed.
2. Bump `versionName` and `versionCode` in `app/build.gradle.kts`.
3. Write a changelog (location TBD — see decision #6 above).
4. Build the signed release APK. **Do not run signing commands automatically** — signing requires a passphrase. Prompt the user to build via Android Studio (Build → Generate Signed APK) or their preferred CLI flow, then wait for confirmation.
5. Create a release commit: `Release X.Y.Z` with `--signoff`.
6. Tag (lightweight, `X.Y.Z`).
7. Push commit and tag on user confirmation.
8. (Optional) GPG-sign the APK and upload to the chosen channel.

## Task: $ARGUMENTS
