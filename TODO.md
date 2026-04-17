# Conductor — TODO

Things explicitly out of scope for v0.1. Ordered by "next most likely to
hurt us without it."

## Automated tests

- JUnit5 unit tests for:
  - `Git.parseWorktreePorcelain` — happy path + detached HEAD + locked entries.
  - `Git.generateBranchName` — prefix respected, collision-free under load.
- Integration tests against temp git repos for `WorkspaceService.create` /
  `finish` (ff-only, --no-ff, rebase, squash, dirty guard).
- `BasePlatformTestCase` smoke tests for the three actions — update(),
  actionPerformed() stubs.
- Plugin Verifier (`./gradlew verifyPlugin`) wired into CI.

## Deferred environments

- **Local** — in-repo branch, no separate window. Dropped from v0.1 because
  JetBrains refuses to open two project windows on the same directory; revisit
  if that changes.
- **Docker** — third option from the original three-env sketch.

## Deferred UX

- **Reopen Claude terminal** action — re-run `<script> && claude` in the
  current worktree window when the tab has been closed.
- **Auto-stash on dirty Finish** — offer "stash & continue" in the dirty
  notification. v0.1 refuses outright.
- **Keyboard shortcut** for *New AI Workspace* (e.g. `Ctrl+Shift+N`).
- **Tool window** with a persistent workspace list and per-row status
  (clean/dirty/ahead/behind). `.conductor/` gives us a place to cache state.

## Deferred integrations

- `gh` CLI for PR creation — currently delegated to the JetBrains GitHub
  plugin.
- Per-repo config inside `.conductor/` (e.g. `config.yaml`) overriding
  global settings.

## Edge cases to verify

- **Windows paths** for `Git.isWorktree` / `mainRepoRoot`.
- **Submodules** — `.git` file vs directory detection, `git-common-dir`
  resolution.
- `git worktree remove` behavior when the worktree is on a different volume
  or the path is locked.
