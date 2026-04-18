---
name: commit-and-push
description: Commit code changes and push via Git. Use when the user asks to commit, push, or save their work to the repository.
argument-hint: commit message or description of changes
---

# Commit and Push

You are committing and pushing code changes for **Low Budget Voice Recognition Input**.

## Pre-flight Check: Is This a Git Repo Yet?

This project is **not yet under git** as of milestone 2. Before doing anything else:

```bash
git -C /home/yhh/AndroidStudioProjects/LowBudgetVoiceRecognitionInput rev-parse --is-inside-work-tree 2>/dev/null
```

If this returns nothing or an error:
- Ask the user whether they want to initialize the repository now (`git init`, add a `.gitignore` for Android Studio, make an initial commit).
- Do **not** silently `git init` — confirm the intended remote (likely `github.com:hiroshiyui/LowBudgetVoiceRecognitionInput.git` based on the existing GitHub presence) and the initial branch name.

Once the repo exists, the rest of this skill applies.

## Commit Message Convention

- **Subject line**: imperative mood, concise summary of the change (e.g. "Add VoiceImeService skeleton", "Fix AudioRecord leak on cancellation").
- **Body** (optional): explain *why* the change was made, not just what. Reference the relevant milestone (e.g. "Part of M2: audio capture + waveform display").
- **Signed-off-by trailer**: use `--signoff` so the user's identity is attached.
- No conventional commits prefix (no `feat:`, `fix:`, etc.) — plain English subject lines.

### Subject Line Patterns

- `Add ...` — new feature, file, or module
- `Fix ...` — bug fix
- `Update ...` — dependency or content update
- `Remove ...` — deletion of code or files
- `Refactor: ...` — code restructuring
- `Style: ...` — formatting or cosmetic changes
- `Upgrade ...` — dependency version upgrades
- `Bump version to X.Y.Z` — version bumps
- `Release X.Y.Z` — release commits (handled by `/release-engineering`)

## Workflow

1. **Review changes** — `git status` and `git diff` to understand what will be committed.
2. **Stage files** — add specific files by name rather than `git add -A`. Be careful not to stage:
   - Sensitive files (`.env`, signing keys, `local.properties` if it contains paths)
   - Build artifacts (`app/build/`, `.gradle/`, `.idea/`)
   - Downloaded model files (anything under `app/src/main/assets/models/` or downloaded to `filesDir` won't be in the tree, but double-check nothing slipped in)
3. **Commit** — use `--signoff`. Pass the message via HEREDOC for proper formatting:
   ```bash
   git commit --signoff -m "$(cat <<'EOF'
   Subject line here

   Optional body explaining why.

   Co-Authored-By: Claude <noreply@anthropic.com>
   EOF
   )"
   ```
4. **Push** — always confirm with the user before pushing. Default push target is `origin` (typically GitHub). If a `gitlab` mirror remote is added later, push there only on explicit request.

## Branch Conventions

To be established once the repo is initialized. Suggested defaults (mirroring the user's other Android projects):
- `master` — main/stable
- `current` — active development
- Feature branches: descriptive names (e.g. `m3-model-downloader`)

Never force-push to `master` or `current` without explicit user approval.

## Task: $ARGUMENTS
