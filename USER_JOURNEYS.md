# Conductor — User Journeys

These are the flows the v0.1 plugin must support. Each step lists the user action → what the plugin does → what the user sees. Edge cases are called out inline. This document is the canonical UX spec — the action/dialog implementation and the README's manual test checklist both follow from here.

**Model.** An AI Workspace is a git worktree + a dedicated IDE window + a terminal tab running `claude`. One workspace = one isolated Claude session with its own branch, working directory, `.idea/`, and changelists. v0.1 supports only worktree-backed workspaces; a local-branch variant was considered and dropped because JetBrains refuses to open two project windows on the same directory, which made the isolation story incoherent.

## J1 — Start an AI Workspace

**Scenario:** User is on `main` in their main repo window. Wants to try a risky refactor without touching their working dir.

1. *Git → Conductor → New AI Workspace* (or `Shift+Shift` → "New AI Workspace").
2. **Plugin shows `NewWorkspaceDialog`:**
   - Name field pre-filled with `wt/bold-fox-a3f2` (editable).
   - Base branch dropdown (defaults to `main`, lists local branches).
   - [Cancel] [Create].
3. User clicks **Create**.
4. `Task.Backgroundable` runs: `git worktree add -b wt/bold-fox-a3f2 ../<repo>-worktrees/bold-fox-a3f2 main`.
5. New IDE window opens at the worktree path. Original window stays open and unchanged.
6. If `settings.startupScriptPath` is set, the script runs in the new window's working dir; output goes to a notification + `idea.log`.
7. Plugin opens a terminal tab named `bold-fox-a3f2 · claude` in the new window and auto-runs `claude`. The command is hardcoded to `claude` in v0.1 and will become configurable in a later release.
8. Notification in the new window: "AI Workspace `wt/bold-fox-a3f2` ready."

**Edges:**
- Worktree dir already exists → error notification, no window opened.
- Base branch doesn't exist → error notification.
- `git worktree add` fails (e.g. branch exists) → notification with stderr; no partial state.
- `claude` not on PATH → tab opens, command fails visibly in the terminal; workspace is otherwise usable.

## J2 — See all active AI Workspaces

**Scenario:** User has 2 AI Workspaces in flight; wants to switch.

1. *Git → Conductor → List AI Workspaces*.
2. `JBPopupFactory` list popup, one row per workspace:
   ```
   wt/bold-fox-a3f2     ../conductor-worktrees/bold-fox-a3f2
   wt/zen-owl-19bc      ../conductor-worktrees/zen-owl-19bc        ← current
   ```
   Current workspace is annotated and selected by default.
3. User selects another row + Enter → opens (or focuses) that worktree's IDE window.

**Edges:**
- No workspaces → popup shows "No active AI Workspaces. Create one with New AI Workspace."
- Worktree dir exists on disk but branch was deleted → still listed; selecting it offers "Remove stale worktree?".

## J3 — Finish an AI Workspace by merging

**Scenario:** User is in a worktree window, work is committed, ready to merge into `main`.

1. *Git → Conductor → Finish AI Workspace* (action is enabled because `WorkspaceService.current()` returns the current worktree).
2. **Plugin shows `FinishWorkspaceDialog`:**
   - Header: "Finish `wt/bold-fox-a3f2`"
   - Strategy radio: **Merge into [base dropdown=main]** (default) / **Discard**.
   - Checkbox: "Delete branch after merge" (default on).
   - [Cancel] [Finish].
3. User clicks **Finish**.
4. `Task.Backgroundable` runs:
   - Pre-check: `git status --porcelain` in worktree. If dirty → confirm dialog "Worktree has uncommitted changes. Continue?".
   - In **main repo** (not the worktree): `git checkout main && git merge --no-ff wt/bold-fox-a3f2`.
   - On merge conflict → abort merge, notify "Merge conflict — resolve manually in main repo. Worktree preserved.", **stop here** (do not remove worktree).
   - On success: close current project window (`ProjectManager.closeAndDispose`), then `git worktree remove <path>`, then `git branch -d wt/bold-fox-a3f2`.
5. User is now in the main repo window on `main` with the merge commit.

**Edges:**
- Currently checked-out branch in main repo conflicts with checkout → abort, surface stderr.
- `git worktree remove` refuses (uncommitted/locked) → offer `--force` in a follow-up confirm.

## J4 — Discard an AI Workspace

**Scenario:** Experiment didn't pan out; throw it away.

1. *Finish AI Workspace* → **Discard** → Finish.
2. Confirm dialog: "Discard `wt/bold-fox-a3f2`? This deletes the branch and worktree. Unmerged commits will be lost."
3. On confirm: close window → `git worktree remove --force <path>` → `git branch -D <branch>`.

**Edges:**
- Discard invoked from the main repo window while the worktree's window is also open → still allowed; close the worktree's window first, then remove. (Decision: allow from anywhere — simpler.)

## J5 — Open an existing worktree window

**Scenario:** User re-opens a worktree project that Conductor created previously (or opens any worktree).

1. Project opens. `ConductorStartupActivity` runs:
   - Checks `Git.isWorktree(projectRoot)` — if true, runs the configured startup script, then opens a `<name> · claude` terminal tab and auto-runs `claude` in it.
   - Otherwise: silent no-op. **No first-run popup, no settings nag.**
2. *Git → Conductor* menu is present and ready.

## J6 — Plugin acts on a non-git directory

**Scenario:** User opens a folder that isn't a git repo and clicks *New AI Workspace*.

1. Action is **disabled** in the menu (`update()` checks for a `.git`).
2. If somehow invoked → notification: "Conductor requires a git repository."
