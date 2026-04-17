# Conductor — User Journeys

These are the flows the v0.1 plugin must support. Each step lists the user action → what the plugin does → what the user sees. Edge cases are called out inline. This document is the canonical UX spec — the action/dialog implementation and the README's manual test checklist both follow from here.

**Model.** An AI Workspace is a git worktree + a dedicated IDE window + a terminal tab running a configured startup command (defaulting to `claude`). One workspace = one isolated Claude session with its own branch, working directory, `.idea/`, and changelists. v0.1 supports only worktree-backed workspaces; a local-branch variant was considered and dropped because JetBrains refuses to open two project windows on the same directory, which made the isolation story incoherent.

## Identity & Detection

Each Conductor-managed worktree is marked by a `.conductor/` directory at its root, written at J1 creation time. This is the single source of truth for "is this a Conductor workspace?":

- **J2 (list)** filters `git worktree list --porcelain` by the presence of `.conductor/`. The old `wt/` branch-name convention is no longer load-bearing. Non-Conductor worktrees are omitted (or shown dimmed — final call during implementation).
- **J5 (open existing)** detection is: "is the project root a git worktree AND does `.conductor/` exist at its root?" Unrelated worktrees opened in the IDE get no terminal, no startup script, silent no-op.
- `.conductor/` leaves room for future per-workspace state (logs, metadata, pinned prompts) without another design pass.

## J0 — Configure Conductor

**Scenario:** User installs the plugin and wants to set defaults before first use, or tweak behavior later.

1. *Settings → Tools → Conductor* (standard JetBrains settings location).
2. Settings page exposes:
   - **`settings.startupScriptPath`** — path to a script run in the new workspace's terminal tab. If unset, the terminal falls back to running `claude` directly.
   - **`settings.worktreeRoot`** — parent directory for new worktrees. Defaults to `../<repo>-worktrees/`. Paths are quoted when shelled out.
   - **`settings.defaultMergeStrategy`** — default selection in the Finish dialog's strategy radio. One of: `merge-ff-only`, `merge-no-ff`, `rebase`, `squash`. If unset, the dialog defaults to `merge-no-ff`.
3. [Apply] / [OK] persist to IDE settings.

## J1 — Start an AI Workspace

**Scenario:** User is on the repo's default branch in their main repo window. Wants to try a risky refactor without touching their working dir.

1. *Git → Conductor → New AI Workspace* (or `Shift+Shift` → "New AI Workspace").
2. **Plugin shows `NewWorkspaceDialog`:**
   - Name field pre-filled with `wt/bold-fox-a3f2` (editable).
   - Base branch dropdown. Default is detected — prefer `git symbolic-ref refs/remotes/origin/HEAD`, falling back to the main repo's currently checked-out branch. Lists local branches.
   - [Cancel] [Create].
3. User clicks **Create**.
4. `Task.Backgroundable` runs: `git worktree add -b wt/bold-fox-a3f2 <settings.worktreeRoot>/bold-fox-a3f2 <base-branch>` (paths quoted).
5. Plugin creates `.conductor/` at the new worktree's root **before** opening the IDE window, so J5 detection fires correctly on first open.
6. New IDE window opens at the worktree path. Original window stays open and unchanged.
7. Plugin opens a terminal tab named `bold-fox-a3f2 · claude` in the new window and runs a single chained command:
   - If `settings.startupScriptPath` is set: `<script> && claude` — script and `claude` run in one shell invocation, so there is no race between them.
   - If unset: `claude` alone (zero-config path stays working).
   - The startup script is the single configuration point for what runs in the workspace terminal. Users who want a different agent, different flags, or a wrapper edit their script — the plugin no longer hardcodes `claude` as a separate step.
8. Notification in the new window: "AI Workspace `wt/bold-fox-a3f2` ready."

**Edges:**
- Worktree dir already exists → error notification, no window opened.
- Base branch doesn't exist → error notification.
- `git worktree add` fails (e.g. branch exists) → notification with stderr; no partial state.
- `claude` not on PATH → tab opens, command fails visibly in the terminal; workspace is otherwise usable.

## J2 — See all active AI Workspaces

**Scenario:** User has 2 AI Workspaces in flight; wants to switch.

1. *Git → Conductor → List AI Workspaces*.
2. Source of truth: `git worktree list --porcelain`, filtered and annotated by the presence of `.conductor/` at each worktree root.
3. `JBPopupFactory` list popup, one row per Conductor workspace:
   ```
   wt/bold-fox-a3f2     ../conductor-worktrees/bold-fox-a3f2
   wt/zen-owl-19bc      ../conductor-worktrees/zen-owl-19bc        ← current
   ```
   When invoked from within a Conductor worktree, the current workspace is annotated and selected by default. When invoked from outside a worktree (main repo window, or any non-Conductor project), the popup still lists workspaces but no row is annotated as "current" and nothing is pre-selected.
4. User selects a row + Enter → opens (or focuses) that worktree's IDE window.

**Edges:**
- No Conductor workspaces → popup shows "No active AI Workspaces. Create one with New AI Workspace."
- Worktree dir exists on disk but branch was deleted → still listed (because `.conductor/` is present); selecting it offers "Remove stale worktree?".
- Non-Conductor worktrees in the repo are not shown (or shown dimmed — decide during implementation).

## J3 — Finish an AI Workspace by merging

**Scenario:** User is in a worktree window, work is committed, ready to merge into the base branch.

1. *Git → Conductor → Finish AI Workspace* (action is enabled because `WorkspaceService.current()` returns the current worktree).
2. Pre-check: `git status --porcelain` in worktree. **If dirty → hard error notification** listing the dirty files and pointing the user back to the worktree to commit or stash. Dialog does not open. (Auto-stash is explicitly deferred past v0.1.)
3. **Plugin shows `FinishWorkspaceDialog`:**
   - Header: "Finish `wt/bold-fox-a3f2`"
   - Strategy radio: **merge (ff-only)** / **merge (--no-ff)** / **rebase** / **squash**. Default comes from `settings.defaultMergeStrategy`; if unset, falls back to `merge (--no-ff)`.
   - Base branch dropdown (default = the branch this worktree was created from).
   - Checkbox: "Delete branch after merge" (default on).
   - [Cancel] [Finish].
4. User clicks **Finish**.
5. `Task.Backgroundable` runs. The merge targets the **on-disk main repo** via `GitRepositoryManager` — the main repo's IDE window does not need to be open:
   - `git checkout <base>` in the main repo, then the strategy-appropriate command (`merge --ff-only`, `merge --no-ff`, `rebase`, or `merge --squash` + commit).
   - On merge conflict → abort, notify "Merge conflict — resolve manually in main repo. Worktree preserved.", **stop here** (do not remove worktree).
   - On success: close current project window (`ProjectManager.closeAndDispose`), then `git worktree remove <path>`, then `git branch -d wt/bold-fox-a3f2` (if deletion checkbox on).
6. After a successful merge, trigger a VCS refresh on any open main-repo project window so the merge commit and branch state appear without a manual refresh.
7. User ends up in the main repo window on the base branch with the merge (or rebased / squashed) commit visible.

**Edges:**
- Currently checked-out branch in main repo conflicts with checkout → abort, surface stderr.
- `git worktree remove` refuses (uncommitted/locked) → offer `--force` in a follow-up confirm.

## J4 — Discard an AI Workspace

**Scenario:** Experiment didn't pan out; throw it away.

1. *Git → Conductor → Discard AI Workspace*.
2. Confirm dialog: "Discard `wt/bold-fox-a3f2`? This deletes the branch and worktree. Unmerged commits will be lost."
3. On confirm: close window → `git worktree remove --force <path>` → `git branch -D <branch>`.

**Edges:**
- Discard invoked from the main repo window while the worktree's window is also open → still allowed; close the worktree's window first, then remove. Closing another window may prompt on unsaved scratch files — acceptable for v0.1.

## J5 — Open an existing worktree window

**Scenario:** User re-opens a worktree project that Conductor created previously (or opens any worktree).

1. Project opens. `ConductorStartupActivity` runs:
   - Checks whether `projectRoot` is a git worktree **and** `.conductor/` exists at its root.
   - If both true → opens a `<name> · claude` terminal tab and runs the chained startup command from J1 (step 7) — `<startup-script> && claude`, or just `claude` if no script is configured.
   - If not a worktree, or no `.conductor/` → silent no-op. **No terminal, no script, no first-run popup, no settings nag.**
2. *Git → Conductor* menu is present and ready whenever the repo is a git repo.

## J6 — Plugin acts on a non-git directory

**Scenario:** User opens a folder that isn't a git repo and clicks *New AI Workspace*.

1. Action is **disabled** in the menu (`update()` checks for a `.git`).
2. If somehow invoked → notification: "Conductor requires a git repository."

## Implementation Notes

Flagged here so they aren't lost when this doc becomes the implementation brief.

- **`Git.isWorktree(projectRoot)` needs a spike.** JetBrains API doesn't expose this cleanly. Likely approach: compare `git rev-parse --git-common-dir` with `git rev-parse --git-dir`; they differ inside a worktree. Verify Windows paths and submodule edge cases before committing to the check.
- **`.conductor/` write timing.** Must exist on disk before the IDE window opens on the worktree, otherwise J5 startup won't fire on first open.
- **Path quoting.** `settings.worktreeRoot` is user-editable; quote it in all shell invocations.
- **Out of scope for v0.1 (explicitly deferred):**
  - Submodules.
  - A "Reopen Claude terminal" action for workspaces where the tab was closed.
  - Auto-stash on dirty Finish.
