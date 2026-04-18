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

## 7. Build the plugin

Run the same command the release workflow runs, so a local green build predicts a green release run:

```
./gradlew --no-daemon buildPlugin
```

If it fails, **abort** and surface the failing task. Do not modify any files yet.

Confirm the artifact exists at `build/distributions/conductor-<current-version>.zip` (lowercase; named after the current version since the bump happens next).

> `verifyPlugin` is intentionally **not** run. The `pluginVerifier()` dependency isn't declared (we don't need marketplace-grade verification for self-distribution). If you ever add it back, update this step and the release workflow in lockstep.

## 8. Bump the version

Edit `gradle.properties` to set `pluginVersion = <new-version>`. Preserve surrounding whitespace and other properties exactly.

> Do **not** change `pluginSinceBuild` or `pluginUntilBuild` unless you know what you're doing. `pluginUntilBuild = 999.*` is deliberate: the plugin is self-hosted and we don't want IDEs on the latest builds to silently filter it out of the custom-repo feed (past incident — v0.1.0 was installed to "No plugins found" on IDEs > 2024.3 until we bumped it).

## 9. Add `<change-notes>` entry in plugin.xml

Open `src/main/resources/META-INF/plugin.xml` and prepend a new `<h4>` block to the `<change-notes>` CDATA:

```xml
<h4><new-version></h4>
<ul>
  <li>One-line summary of what changed.</li>
  ...
</ul>
```

This powers the "What's New" tab in the IDE's plugin details panel. Keep older versions listed below — don't truncate history.

The `<description>` block does not need to change for routine releases. The feed generator (`scripts/gen-update-xml.sh`) auto-extracts the first `<p>` from `<description>` at release time for the plugin-repo list row, so the two stay in sync automatically.

## 10. Update CHANGELOG.md

Gather commits since the last release to use as release notes:

- Determine the previous reference: `git describe --tags --abbrev=0` if any tag exists, else use the commit before the current `HEAD~N` range, else the project root. If no prior tag exists, use all commits since the last `## [` section in `CHANGELOG.md` that has a version (fall back to `HEAD` only as a last resort).
- Run `git log <range>..HEAD --no-merges --pretty=format:"- %s"` to list commit subjects.
- Group or deduplicate obvious repeats. Do not fabricate entries — only use commit subjects. Clean up prefixes like `feat:`, `fix:` into plain readable bullets if present.

Update `CHANGELOG.md`:

1. Under `## [Unreleased]`, insert a new section `## [<new-version>] - <YYYY-MM-DD>` (today's date) immediately after it.
2. Move any entries currently under `## [Unreleased]` into the new version section, followed by the newly gathered commit bullets (dedup against what's already there).
3. Leave `## [Unreleased]` empty (header only) for future work.

Keep the existing "Keep a Changelog" preamble untouched.

## 11. Stage and commit

Stage exactly these files (do not use `git add -A`):

- `gradle.properties`
- `CHANGELOG.md`
- `src/main/resources/META-INF/plugin.xml`

Commit with message:

```
Release v<new-version>
```

Use a HEREDOC for the message and include the standard Claude Code `Co-Authored-By` trailer per the repo's commit conventions.

## 12. Tag

```
git tag -a v<new-version> -m "Release v<new-version>"
```

An annotated tag ensures the release workflow has a real tag object to attach notes to.

## 13. Push

Push the commit first, then the tag. Pushing the tag triggers `.github/workflows/release.yml`.

```
git push origin main
git push origin v<new-version>
```

## 14. Watch the release workflow

The release workflow runs the same `./gradlew --no-daemon buildPlugin` plus artifact upload, GitHub Release creation, and a regeneration of `updatePlugins.xml` which is pushed to the `gh-pages` branch. Wait for it:

```
gh run watch $(gh run list --repo devdepot-ai/conductor --workflow=release.yml --limit 1 --json databaseId -q '.[0].databaseId') --repo devdepot-ai/conductor --exit-status
```

If it fails, investigate before claiming the release is done.

## 15. Report

Tell the user:

- New version and tag name
- Files modified (`gradle.properties`, `CHANGELOG.md`, `plugin.xml`)
- That the commit and tag were pushed and the `Release` workflow completed successfully
- Release URL: `https://github.com/devdepot-ai/conductor/releases/tag/v<new-version>`
- Remind them that existing installs will see the update feed refresh at `https://devdepot-ai.github.io/conductor/updatePlugins.xml`; IDE side may require hitting the reload button next to the custom repo URL to pick up the new version immediately.

## Operational gotchas (learned the hard way)

- **Tag deletion is blocked.** Don't try to delete a remote tag to "retry" a broken release — it's shared history and the sandbox will refuse. Fix the cause, bump patch, and re-release. If you need to rerun the same version after fixing CI/workflow, use `gh workflow run release.yml -f version=<x.y.z>` (manual dispatch).
- **Artifact casing.** The zip produced by `buildPlugin` is lowercase: `build/distributions/conductor-<version>.zip`. The release workflow globs `*-${version}.zip` so casing doesn't bite it, but your eyes might.
- **Feed description = first `<p>`.** `scripts/gen-update-xml.sh` pulls the first `<p>` out of `plugin.xml`'s `<description>` CDATA. Keep a sensible opening paragraph — it becomes the one-liner shown under the plugin name in the custom-repo list.
- **gh-pages bootstrap is one-time.** If this skill runs on a brand-new clone of the repo against a fresh GitHub origin where no `gh-pages` branch exists, the first release will fail at the "Publish to gh-pages" step. See `IMPLEMENTATION_PLAN.md` or the original setup commit for the orphan-branch bootstrap dance.
