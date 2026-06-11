# Changelog

All notable changes to Conductor are documented here. The format loosely
follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

## [0.4.0] - 2026-06-11

- New **Check out an existing branch** mode in the New AI Workspace
  dialog: a branch picker over local and remote refs that opens a
  worktree on an existing branch without creating a new one. A remote
  ref (e.g. `origin/feature`) gets a local tracking branch.
- **Open as AI Workspace…** action added to the native Git branches
  popup (positioned under *New Branch from…*); it opens the dialog with
  the selected branch preselected rather than creating immediately.
- The dialog can now **override the startup command** per workspace
  (editable when *Run startup command* is checked) and seed Claude's
  first prompt through a *Message to Claude* field when Claude Code
  integration is active.
- Internal: shared `NewWorkspaceLauncher` backs both the menu action and
  the branch-popup action.

## [0.3.0] - 2026-05-26

- Claude Code integration: live session status surfaces in the
  Conductor tool window. A new **Claude** column in the trunk panel
  and a workspace-panel row reflect Claude Code's lifecycle events
  (`SessionStart`, `UserPromptSubmit`, `PreToolUse`, `PostToolUse`,
  `Stop`, `Notification`, `SessionEnd`), wired through a user-global
  hook installed into `~/.claude/settings.json`.
- First user prompt per workspace is stashed (≤200 chars) into the
  marker as `description` and shown inline in the trunk **Name**
  column and on the workspace panel.
- Stale Claude state files are cleaned up when the workspace IDE
  window closes; a 1h staleness filter covers ungraceful kills.
- New `TerminalPosition` setting (bottom / right / left) snapshots
  into the workspace marker at creation so each workspace honors
  the trunk's preference.
- New `terminalStartCommand` setting captured in marker + settings.
  Resolution order: marker → settings → `claude` when Claude Code
  is detected with hooks installed. The command is typed into the
  visible terminal tab via `sendCommandToExecute`.
- Settings page gains a **Claude Code integration** section with
  detect / install / uninstall / reinstall buttons, plus a one-shot
  install snackbar on first git project open when hooks are missing.

## [0.2.1] - 2026-05-26

- New AI Workspace dialog now uses the IDE's native auto-completing ref
  picker for the base branch field.
- Tool window workspace list rendered as a table with a dedicated
  **Branch** column.
- Internal: `WorkspacePanel.reload()` uses the cached snapshot instead of
  recomputing on every reload.

## [0.2.0] - 2026-05-16

- New AI Workspace dialog gains a **Run startup command after opening**
  checkbox (checked by default). Uncheck to create a workspace without
  auto-running the configured startup command — same behavior as the
  existing **Open Without Startup Command** action, applied at creation
  time. Wired through `CreateSpec` → `WorkspaceService.create` so future
  workspace providers can honor it too.

## [0.1.6] - 2026-04-19

- `Finish AI Workspace` now runs `git push --set-upstream origin <branch>`
  before invoking `gh pr create`, fixing `Head ref must be a branch` /
  `No commits between <base> and <head>` failures when the branch
  hadn't been published yet.
- New **Conductor finish** tab in the Run tool window: each git and
  forge-CLI command executed during Finish (push, PR create, checkout,
  merge, rebase, squash, worktree remove, branch delete) streams its
  command line, stdout, stderr, and exit code so failures are visible
  in full instead of in a truncated balloon notification.
- Release workflow: bump `actions/checkout`, `actions/upload-artifact`,
  `softprops/action-gh-release`, and `peaceiris/actions-gh-pages` to
  Node 24-compatible majors.

## [0.1.5] - 2026-04-19

- Tool window list now bolds the workspace name and appends a green
  **● open** badge when a workspace has an IDE window open in the current
  IDE process; list refreshes automatically as workspace windows open and
  close.
- `Finish AI Workspace` reshaped into publish-and-reap: opens a PR on
  GitHub or Bitbucket and preserves the workspace until the PR merges; a
  new `PrWatcher` polls tracked PRs and reaps merged workspaces. Local
  merge is still available as an optional step.
- Right-click in the tool window selects the target row before showing
  the context menu, so menu actions operate on the intended workspace.
- Tool window right-click menu: add **Open Without Startup Command** —
  opens the workspace without running its configured startup command
  (one-shot; terminal-on-start and ready notification still fire).

## [0.1.4] - 2026-04-18

- New Conductor tool window with trunk (list of workspaces) and workspace
  (current workspace details) modes; right-click menus for Open, Rename, and
  Delete.
- `Rename AI Workspace` action: rewrites `.conductor-workspace.json` in
  place; the marker is now the source of truth for the workspace name.
- Workspace name is persisted in the marker on create (defaulting to the
  branch name) and read back in `enumerate()`, falling back to the
  directory name when the field is absent.
- Tool window list renderer and the `List AI Workspaces` popup now lead
  with the workspace name; branch is shown in gray when it differs.
- Tool window polish: icons, data-provider context, multi-select in the
  trunk list, double-click to open, Refresh toolbar button.
- Pin the startup terminal tab so IDE restarts don't lose it.
- Internal: split logical `Workspace` from the `WorktreeWorkspace`
  implementation to open the seam for future (non-worktree) providers.

## [0.1.3] - 2026-04-18

- Add `conductor@devdepot.ai` as the plugin support email (shown in the
  IDE's plugin details panel).

## [0.1.2]

- Richer plugin metadata: vendor homepage link, detailed overview with feature
  list, per-version change notes shown in the "What's New" tab.
- Feed generator now derives the plugin-repo list description from
  `plugin.xml`, keeping the two in sync.

## [0.1.1]

- Raise `until-build` to `999.*` so the plugin resolves on any modern
  JetBrains IDE build. Self-hosted distribution doesn't need marketplace-grade
  version gating.

## [0.1.0]

- Initial release: AI Workspaces backed by git worktrees, isolated IDE windows,
  startup terminal running `claude` (or a configured script chained with it),
  New/List/Finish/Discard actions, `.conductor/settings.json` for per-workspace
  config.
