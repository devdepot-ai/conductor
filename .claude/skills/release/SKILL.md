---
name: release
description: Build, verify, bump version, update CHANGELOG, commit, tag, and push a new Conductor release.
user_invocable: true
---

Follow these steps **sequentially**. If any step fails, stop immediately and report which step failed and why. Do not proceed past a failed step.

This project is a JetBrains IntelliJ plugin. The GitHub Actions release workflow in `.github/workflows/release.yml` is triggered when a `v*` tag is pushed, so the final `git push` of the tag is what cuts the release.

## 1. Ensure clean working tree

Run `git status --porcelain`. If there is any output (uncommitted changes, untracked files), **abort** and tell the user to commit or stash first. The release must be reproducible from `HEAD`.

## 2. Ensure on main branch

Run `git branch --show-current`. If not `main`, **abort** and tell the user to switch.

## 3. Ensure up-to-date with origin

Run `git fetch origin main` followed by `git status -sb`. If the local branch is behind or has diverged from `origin/main`, **abort** and tell the user to reconcile first.

## 4. Read current version

Parse `pluginVersion` from `gradle.properties`. This is the current version (e.g. `0.1.0`).

## 5. Determine the new version

- If the user passed an explicit version as an argument (e.g. `/release 0.2.0` or `/release patch|minor|major`), use that.
- `patch`/`minor`/`major` bumps apply semver to the current version.
- Otherwise, ask the user which bump they want. Default suggestion: `patch`.

Compute the new version string (e.g. `0.1.1`). Validate it is strictly greater than the current version and matches `^\d+\.\d+\.\d+$`.

## 6. Check the tag does not already exist

Run `git rev-parse -q --verify refs/tags/v<new-version>`. If it resolves, **abort** — the tag already exists.

## 7. Build and verify the plugin

Run the same commands CI runs, so a local green run means the release workflow will also succeed:

```
./gradlew --no-daemon buildPlugin verifyPlugin
```

If either task fails, **abort** and surface the failing task. Do not modify any files yet.

Confirm the artifact exists at `build/distributions/Conductor-<current-version>.zip` (it is named after the current version since the bump happens next).

## 8. Bump the version

Edit `gradle.properties` to set `pluginVersion = <new-version>`. Preserve surrounding whitespace and other properties exactly.

## 9. Update CHANGELOG.md

Gather commits since the last release to use as release notes:

- Determine the previous reference: `git describe --tags --abbrev=0` if any tag exists, else use the commit before the current `HEAD~N` range, else the project root. If no prior tag exists, use all commits since the last `## [` section in `CHANGELOG.md` that has a version (fall back to `HEAD` only as a last resort).
- Run `git log <range>..HEAD --no-merges --pretty=format:"- %s"` to list commit subjects.
- Group or deduplicate obvious repeats. Do not fabricate entries — only use commit subjects. Clean up prefixes like `feat:`, `fix:` into plain readable bullets if present.

Update `CHANGELOG.md`:

1. Under `## [Unreleased]`, insert a new section `## [<new-version>] - <YYYY-MM-DD>` (today's date) immediately after it.
2. Move any entries currently under `## [Unreleased]` into the new version section, followed by the newly gathered commit bullets (dedup against what's already there).
3. Leave `## [Unreleased]` empty (header only) for future work.

Keep the existing "Keep a Changelog" preamble untouched.

## 10. Stage and commit

Stage exactly these files (do not use `git add -A`):

- `gradle.properties`
- `CHANGELOG.md`

Commit with message:

```
Release v<new-version>
```

Use a HEREDOC for the message and include the standard Claude Code `Co-Authored-By` trailer per the repo's commit conventions.

## 11. Tag

```
git tag -a v<new-version> -m "Release v<new-version>"
```

An annotated tag ensures the release workflow has a real tag object to attach notes to.

## 12. Push

Push the commit first, then the tag. Pushing the tag triggers `.github/workflows/release.yml`.

```
git push origin main
git push origin v<new-version>
```

## 13. Report

Tell the user:

- New version and tag name
- Files modified (`gradle.properties`, `CHANGELOG.md`)
- That the commit and tag were pushed
- That the GitHub Actions `Release` workflow is now running — link: `https://github.com/devdepot-ai/conductor/actions/workflows/release.yml`
- A reminder that the release will appear at `https://github.com/devdepot-ai/conductor/releases/tag/v<new-version>` once the workflow completes, and that `public/updatePlugins.xml` on the `gh-pages` branch will be refreshed for the self-hosted update feed.
