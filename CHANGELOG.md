# Changelog

All notable changes to Conductor are documented here. The format loosely
follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

## [0.1.1]

- Raise `until-build` to `999.*` so the plugin resolves on any modern
  JetBrains IDE build. Self-hosted distribution doesn't need marketplace-grade
  version gating.

## [0.1.0]

- Initial release: AI Workspaces backed by git worktrees, isolated IDE windows,
  startup terminal running `claude` (or a configured script chained with it),
  New/List/Finish/Discard actions, `.conductor/settings.json` for per-workspace
  config.
