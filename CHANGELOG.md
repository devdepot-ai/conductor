# Changelog

All notable changes to Conductor are documented here. The format loosely
follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

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
