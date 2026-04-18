# Changelog

All notable changes to Conductor are documented here. The format loosely
follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

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
