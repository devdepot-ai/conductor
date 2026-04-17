# Conductor

A JetBrains plugin that turns each Claude session into an **AI Workspace**: a
git worktree + its own IDE window + a terminal tab running your configured
startup command (defaulting to `claude`). One workspace = one isolated agent
with its own branch, working directory, `.idea/`, and changelists.

UX spec: [`USER_JOURNEYS.md`](./USER_JOURNEYS.md). Implementation brief:
[`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md). Deferred work:
[`TODO.md`](./TODO.md).

## Prerequisites

- **JDK 17** on `PATH` (`java -version` should show 17). `brew install openjdk@17` on macOS.
- **git** CLI on `PATH`.
- **`claude`** CLI on `PATH`. If missing, the workspace terminal tab still opens
  but the command errors visibly — the workspace itself is otherwise usable.

## Dev loop

```
./gradlew runIde
```

Boots a **separate sandboxed IDE** with the plugin auto-loaded. Its config
lives in `build/idea-sandbox/config/`, entirely independent of your real
IDE. First boot is slow — Gradle downloads the IntelliJ Community
distribution into `~/.gradle/caches/modules-2/`.

**There is no hot reload.** Code changes require stopping the sandbox and
re-running `./gradlew runIde`.

## Pointing the sandbox at a test repo

Inside the sandbox IDE, *File → Open* a throwaway git repo. The simplest
setup:

```
mkdir -p /tmp/conductor-playground && cd /tmp/conductor-playground \
  && git init && git commit --allow-empty -m init
```

Conductor actions appear under *Git → Conductor* and are searchable with
`Shift+Shift` (e.g. "New AI Workspace").

Configure defaults under *Settings → Tools → Conductor*:
- **Startup script path** — blank = run just `claude`. Set a path to run
  `<script> && claude` in a single shell invocation.
- **Worktree root** — parent dir for new worktrees. Blank = sibling
  `../<repo>-worktrees/`.
- **Default merge strategy** — ff-only / --no-ff / rebase / squash.

## Logs

```
tail -f build/idea-sandbox/system/log/idea.log
```

In plugin code:

```kotlin
private val log = Logger.getInstance(MyClass::class.java)
log.info("…"); log.warn("…"); log.error("…")
```

All land in `idea.log`.

## Manual test checklist

Run the full list after any meaningful change. Each item maps back to a
journey in `USER_JOURNEYS.md`.

- [ ] `./gradlew runIde` boots without errors in `idea.log`.
- [ ] **J0** — *Settings → Tools → Conductor* shows three fields
      (`startupScriptPath`, `worktreeRoot`, `defaultMergeStrategy`); edits
      persist across sandbox restarts.
- [ ] **J1** — *Git → Conductor → New AI Workspace* opens dialog; default name
      matches `wt/<word>-<word>-<hex>`; base dropdown defaults to the detected
      default branch (origin HEAD, or current branch if HEAD not set). Submit
      → new IDE window opens at `<worktreeRoot>/<slug>`; **`.conductor/`
      exists at its root**; original window unchanged; `git worktree list`
      shows it; terminal tab `<name> · claude` opens and runs the chained
      `<script> && claude` (or just `claude`); "AI Workspace ready"
      notification appears.
- [ ] **J1 edges** — worktree dir already exists → error notification, no
      window; invalid base branch → error, no partial state; `claude` not on
      PATH → tab still opens, command fails visibly, workspace otherwise
      usable.
- [ ] **J2** — *List AI Workspaces* with 2+ Conductor workspaces shows one
      row each; non-Conductor worktrees are omitted. Invoked from inside a
      worktree: current row annotated and preselected. Invoked from outside
      any worktree: no row annotated/preselected. Selecting a row
      opens/focuses its window. Empty repo shows "No active AI Workspaces".
- [ ] **J3** — *Finish AI Workspace* enabled only in a worktree window. Dirty
      worktree → **hard-error notification listing dirty files; dialog does
      not open**. Clean worktree → dialog opens with strategy radio `ff-only`
      / `--no-ff` / `rebase` / `squash` / `discard`; default matches
      `settings.defaultMergeStrategy`. Merge success: target branch receives
      the commit, worktree window closes, worktree is removed, branch
      deleted; main repo window's VCS view refreshes to show the new commit
      without manual reload (main repo window does not need to be open
      during the merge).
- [ ] **J3 edges** — merge/rebase conflict → abort + "resolve manually"
      notification, worktree preserved; `worktree remove` refusal →
      suggestion to retry with Discard.
- [ ] **J4** — *Finish → Discard* → confirm dialog mentions lost commits; on
      confirm branch + worktree disappear, window closes. Discard invocable
      from main repo window too (via *List AI Workspaces* → selection).
- [ ] **J5** — Re-opening a previously created worktree project (with
      `.conductor/` present) opens the `<name> · claude` terminal tab and
      runs the chained startup command automatically; no first-run popups.
      Opening a non-Conductor worktree (no `.conductor/`) does nothing —
      silent no-op.
- [ ] **J6** — In a non-git directory, *Git → Conductor → New AI Workspace*
      is disabled; invoking via `Shift+Shift` shows
      "Conductor requires a git repository."

## Building a distributable

```
./gradlew buildPlugin
```

Produces `build/distributions/conductor-0.1.0.zip`. Install in your real
JetBrains IDE via *Settings → Plugins → ⚙ → Install Plugin from Disk…*.

## Troubleshooting

- **Plugin not loading** → check `build/idea-sandbox/system/log/idea.log`
  for `PluginException`.
- **Sandbox state weird** → `./gradlew clean` removes `build/idea-sandbox/`.
- **JBR version mismatch** → ensure JDK 17 (`./gradlew --version`).
- **Gradle/plugin version conflicts** — this project uses Gradle 8.10 via
  the wrapper (`./gradlew`). Don't invoke system `gradle` directly; it may
  be a newer incompatible major version.
