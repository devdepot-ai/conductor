# Conductor — Implementation Plan

## Context

We're building a JetBrains plugin (in the `conductor` repo, originally containing only `PLAN.md`) that streamlines the create → work → finish loop for short-lived branches used as isolated Claude sessions.

The central abstraction is an **AI Workspace**: a git worktree + a dedicated IDE window + a terminal tab running a user-configurable startup command (defaulting to `claude`). One workspace = one isolated Claude session with its own branch, working directory, `.idea/`, and changelists.

**v0.1 scope:**
- **Worktree-only.** A local-branch variant was considered and dropped — JetBrains refuses to open two project windows on the same directory, which made the isolation story incoherent. See `USER_JOURNEYS.md` for the full rationale.
- **`.conductor/` marker folder** written at the worktree root at creation time is the single source of truth for "is this a Conductor workspace?" — used by the list popup (J2) and the open-worktree startup activity (J5). No persistent store beyond on-disk state.
- Workspace list is derived from `git worktree list --porcelain` filtered by the presence of `.conductor/`.
- Terminal tab named `<name> · claude` runs a **single chained shell command**: `<startup-script> && claude` if a startup script is configured, otherwise just `claude`. The startup script is the single configuration point — users who want a different agent, different flags, or a wrapper edit their script. No separate `claude` invocation is hardcoded in the plugin.
- **Settings page** (J0) under *Settings → Tools → Conductor* with three settings: `startupScriptPath`, `worktreeRoot`, `defaultMergeStrategy`.
- **Skip `gh` / PR creation** — JetBrains' built-in GitHub plugin already handles PRs.
- **Skip automated tests** in v0.1 — manual testing checklist in README, automated tests on the TODO list.
- README must explain JetBrains plugin dev workflow + manual testing (first time building a JetBrains plugin).

**Outcome:** A working plugin loadable via `./gradlew runIde` that can create, list, finish (merge/rebase/squash), and discard AI Workspaces — plus a README that lets the user iterate without prior plugin experience.

The canonical UX spec lives in [`USER_JOURNEYS.md`](./USER_JOURNEYS.md) (J0–J6). Phase 4 (Actions + UI) and Phase 5 (Settings + startup) implement against it; the README's manual test checklist mirrors it.

---

## Architecture

### Core abstraction

```kotlin
data class Workspace(
    val name: String,            // display name, e.g. "bold-fox-a3f2"
    val branch: String,          // e.g. "wt/bold-fox-a3f2"
    val path: Path,              // worktree dir
    val isCurrent: Boolean,      // is the current Project this worktree?
)
```

`WorkspaceService` is a project-level service that:
- `list()` — parses `git worktree list --porcelain` (relative to the main repo) and filters to worktrees whose root contains a `.conductor/` directory. The `wt/` branch-name convention is no longer used for detection.
- `current()` — returns the `Workspace` whose `path` matches `project.basePath` and has `.conductor/`, or `null` otherwise.
- `create(name, baseBranch)` — runs `git worktree add -b <branch> <worktreeRoot>/<slug> <base>` in the main repo, creates `.conductor/` at the new worktree's root **before** opening the IDE window, then opens a new IDE window at the worktree path. The startup activity on that window opens the terminal tab with the chained startup command.
- `finish(workspace, strategy, options)` — Merge (ff-only / --no-ff / squash), Rebase, or Discard. See journeys J3/J4 for the exact command sequence. Runs against the main repo via `GitRepositoryManager` (located via `git rev-parse --git-common-dir`); the main repo's IDE window does **not** need to be open.

Worktree operations live in `git/Git.kt` as thin wrappers around `GeneralCommandLine`; `WorkspaceService` composes them with IDE-side effects (open/close window, run terminal tab, VCS refresh).

### File layout

```
conductor/
├── README.md                              # NEW — dev workflow + manual test checklist
├── USER_JOURNEYS.md                       # canonical UX spec (J0–J6)
├── IMPLEMENTATION_PLAN.md                 # this file
├── TODO.md                                # NEW — automated tests + future envs (Local, Docker)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties                      # pluginGroup=dev.devdepot.conductor, IC 2024.1, JVM 17
├── gradle/wrapper/
└── src/main/
    ├── resources/META-INF/
    │   └── plugin.xml
    └── kotlin/dev/devdepot/conductor/
        ├── workspace/
        │   ├── Workspace.kt
        │   ├── WorkspaceService.kt        # project service; list/create/finish/current
        │   └── ConductorMarker.kt         # write/detect `.conductor/` folder
        ├── git/
        │   └── Git.kt                     # GeneralCommandLine wrapper, porcelain parser, isWorktree, detectDefaultBranch, generateBranchName
        ├── actions/
        │   ├── NewWorkspaceAction.kt
        │   ├── ListWorkspacesAction.kt
        │   └── FinishWorkspaceAction.kt
        ├── ui/
        │   ├── NewWorkspaceDialog.kt      # name field + base branch dropdown
        │   └── FinishWorkspaceDialog.kt   # strategy radio (ff-only / --no-ff / rebase / squash / discard) + base dropdown + "delete branch" checkbox
        ├── ide/
        │   ├── ProjectOpener.kt           # ProjectManagerEx.openProject + closeAndDispose
        │   └── ClaudeTerminalLauncher.kt  # opens named terminal tab, runs chained `<script> && claude` (or just `claude`)
        ├── settings/
        │   ├── ConductorSettings.kt       # PersistentStateComponent
        │   └── ConductorConfigurable.kt   # Settings → Tools → Conductor UI panel (J0)
        └── startup/
            └── ConductorStartupActivity.kt
```

### Naming / branding

Plugin id: `dev.devdepot.conductor`. Display name: `Conductor`. Action group: `Conductor` under `VcsGroups`. Notification group id: `Conductor`.

---

## Build phases

### Phase 1 — Gradle scaffolding + README
- `settings.gradle.kts`, `build.gradle.kts` using `org.jetbrains.intellij.platform` v2.x
- `gradle.properties` (IC 2024.1, JVM 17)
- `./gradlew wrapper --gradle-version 8.10`
- Minimal `plugin.xml` (id, name, vendor `devdepot`, depends on `com.intellij.modules.platform` + `Git4Idea`, no actions yet)
- One placeholder action so `runIde` proves the plugin loads
- **Write `README.md`** — see contents below; links to `USER_JOURNEYS.md` as the UX spec
- **Write `TODO.md`** — automated tests, additional environments (Local, Docker), `gh` PR flow, keyboard shortcuts, tool window, plugin verifier in CI, deferred items from `USER_JOURNEYS.md` (submodules, reopen-terminal action, auto-stash)
- Verify: `./gradlew runIde` boots a sandboxed IDE with the plugin visible in *Settings → Plugins*

### Phase 2 — `git/Git.kt` + `WorkspaceService`
- `Git` helper: `exec(args, cwd)` returns `(exitCode, stdout, stderr)`. Wraps `GeneralCommandLine` + `CapturingProcessHandler` with 30s timeout, `ParentEnvironmentType.CONSOLE`. All user-supplied paths (including `settings.worktreeRoot`) are quoted when interpolated into command lines.
- Porcelain parser for `git worktree list --porcelain` — returns `(path, branch, HEAD)` tuples.
- `isWorktree(projectPath)` — **needs a spike.** JetBrains API doesn't expose this cleanly. Likely approach: compare `git rev-parse --git-common-dir` with `git rev-parse --git-dir`; they differ inside a worktree. A `.git` file (not a directory) is a secondary signal. Windows + submodule edge cases need explicit verification.
- `mainRepoRoot(worktreePath)` — `git rev-parse --git-common-dir` + canonicalize to the main repo working dir.
- `detectDefaultBranch(mainRepo)` — `git symbolic-ref refs/remotes/origin/HEAD`, falling back to the main repo's currently checked-out branch. No `main` hardcode.
- `generateBranchName()` → `wt/<adjective>-<noun>-<4hex>` (small wordlists baked in). `wt/` prefix is used **only** for branch-name generation; workspace detection uses `.conductor/`.
- `ConductorMarker` — `write(worktreeRoot)` creates the `.conductor/` directory (empty in v0.1, leaves room for metadata later); `isPresent(path)` checks for it.
- `WorkspaceService.list(repoRoot)` — parses porcelain, filters to entries whose root contains `.conductor/`, excludes the main worktree.
- `WorkspaceService.current()` — resolves the current project's path against the filtered list (null if project is not a Conductor worktree).
- `WorkspaceService.create(name, base)` — `git worktree add -b wt/<name> <worktreeRoot>/<name> <base>` executed in the main repo, where `<worktreeRoot>` comes from `settings.worktreeRoot` (default sibling dir `<repoName>-worktrees/`). On success: writes `.conductor/` at the new worktree root, **then** opens the IDE window so the startup activity on first open detects the marker.
- `WorkspaceService.finish(workspace, strategy, deleteBranch)` — located against the main repo via `GitRepositoryManager` (main repo IDE window need not be open).
  - **Dirty check** runs first via `git status --porcelain` in the worktree. If dirty, returns a structured error; the action layer surfaces it as a **hard-error notification** listing the dirty files and never opens the dialog. No "Continue?" confirm.
  - Strategies:
    - `merge-ff-only` → `git checkout <base> && git merge --ff-only <branch>`
    - `merge-no-ff` → `git checkout <base> && git merge --no-ff <branch>`
    - `rebase` → `git rebase <base>` on the worktree branch, then fast-forward `<base>`
    - `squash` → `git checkout <base> && git merge --squash <branch> && git commit`
  - On merge/rebase conflict → abort, notify "Conflict — resolve manually in main repo. Worktree preserved.", stop here.
  - On success: close worktree window, `git worktree remove <path>`, `git branch -d <branch>` (if `deleteBranch`), then trigger a VCS refresh on any open main-repo project window so the new commit + branch state appear without a manual refresh.
  - Discard variant: close window, `git worktree remove --force <path>`, `git branch -D <branch>`.

### Phase 3 — `ProjectOpener` + `ClaudeTerminalLauncher`
- `ProjectOpener.openInNewWindow(path)` via `ProjectManagerEx.getInstanceEx().openProject(path, OpenProjectTask(forceOpenInNewFrame = true))`.
- `ProjectOpener.closeProject(project)` via `ProjectManager.getInstance().closeAndDispose(project)`.
- `ClaudeTerminalLauncher.launch(project, tabName, startupScriptPath?)` — uses `org.jetbrains.plugins.terminal.TerminalToolWindowManager` to open a new tab named `<tabName> · claude` in the project's working directory and send a **single chained command**:
  - If `startupScriptPath` is set: `"<script>" && claude\n` (script path quoted).
  - Otherwise: `claude\n`.
  - Script output lands in the terminal directly — no separate `GeneralCommandLine` invocation, no notification/idea.log capture. Failure to launch the tab itself surfaces as a notification but doesn't block workspace creation.

### Phase 4 — Actions + UI
Implement against the journeys in [`USER_JOURNEYS.md`](./USER_JOURNEYS.md):

- **`NewWorkspaceAction` → `NewWorkspaceDialog`** (J1). Pre-filled `wt/<word>-<word>-<hex>` name + base branch dropdown defaulted to `detectDefaultBranch(mainRepo)` (origin HEAD, falling back to currently checked-out branch). Submit → `Task.Backgroundable` runs `WorkspaceService.create(...)`. On success the *new* window's startup activity handles the terminal tab + chained startup command.
- **`ListWorkspacesAction`** (J2) → `JBPopupFactory.createListPopup` showing one row per Conductor workspace (`git worktree list --porcelain` filtered by `.conductor/`).
  - Invoked from inside a Conductor worktree: the current row is annotated (`← current`) and preselected.
  - Invoked from outside any worktree (main repo window or non-Conductor project): popup still lists workspaces but no row is annotated as current and nothing is pre-selected.
  - Selecting a row opens or focuses that worktree's IDE window.
  - Empty state: "No active AI Workspaces. Create one with New AI Workspace."
  - Stale entry (branch missing but `.conductor/` present) → offer "Remove stale worktree?".
  - Non-Conductor worktrees are not shown (or shown dimmed — final call during implementation).
- **`FinishWorkspaceAction` → `FinishWorkspaceDialog`** (J3, J4). Action enabled only when `WorkspaceService.current() != null` (J6: disabled in non-git dirs too — `update()` checks `.git`).
  - On invocation the action runs the **dirty-check guard first**; if `git status --porcelain` shows uncommitted changes, it shows a hard-error notification listing the dirty files pointing the user back to commit/stash, and does **not** open the dialog.
  - Otherwise the dialog opens with: header `Finish \`<branch>\``, **strategy radio** `merge (ff-only)` / `merge (--no-ff)` / `rebase` / `squash` / `discard` — default from `settings.defaultMergeStrategy`, falling back to `merge (--no-ff)` if unset; base branch dropdown defaulting to the branch this worktree was created from; checkbox "Delete branch after merge" (default on, disabled for Discard). Submit → `Task.Backgroundable` runs `WorkspaceService.finish(...)`. Discard path shows the "unmerged commits will be lost" confirm from J4.

All three actions live under `<group id="Conductor.Menu">` attached to `VcsGroups` (appears as *Git → Conductor → …*) and are searchable via `Shift+Shift`.

### Phase 5 — Settings UI + startup activity (J0, J5)
- `ConductorSettings` (PersistentStateComponent, project-level):
  - `startupScriptPath` (empty = skip; terminal tab runs just `claude`)
  - `worktreeRoot` (empty = auto: `<repoName>-worktrees/` sibling of the repo)
  - `defaultMergeStrategy` (default `merge-no-ff`; one of `merge-ff-only | merge-no-ff | rebase | squash`)
  - `branchPrefix` (`wt/`, used for **branch-name generation only** — no longer used for workspace detection)
- `ConductorConfigurable` (`Configurable`, registered as `projectConfigurable` under parent `tools`, display name `Conductor`): fields bound to the three user-facing settings (`startupScriptPath`, `worktreeRoot`, `defaultMergeStrategy`). Standard `isModified` / `apply` / `reset` flow.
- `ConductorStartupActivity` (`ProjectActivity`) fires on every project open:
  - If `Git.isWorktree(projectRoot)` **and** `ConductorMarker.isPresent(projectRoot)`:
    1. Open a `<name> · claude` terminal tab via `ClaudeTerminalLauncher`, passing `settings.startupScriptPath`. The tab runs the chained `<script> && claude` command (or just `claude` if no script).
    2. Post "AI Workspace `<branch>` ready." notification.
  - Otherwise (not a worktree, or worktree without `.conductor/`): silent no-op — no terminal, no script, no first-run popup, no settings nag. (J5, J6.)
- Notification group registered in `plugin.xml`.

This means `WorkspaceService.create` does **not** directly launch the terminal — it creates the worktree, writes `.conductor/`, and opens the new window. The new window's startup activity handles the terminal tab. That keeps create-on-disk and create-on-open paths symmetric (J5 reuses the same code as the tail of J1).

### Phase 6 — Manual verification
Run through the README's manual test checklist end-to-end inside `./gradlew runIde`. Each item maps back to a journey in `USER_JOURNEYS.md`.

---

## `plugin.xml` essentials

```xml
<idea-plugin>
  <id>dev.devdepot.conductor</id>
  <name>Conductor</name>
  <vendor>devdepot</vendor>
  <description>AI Workspaces — isolated Claude sessions backed by git worktrees.</description>

  <depends>com.intellij.modules.platform</depends>
  <depends>Git4Idea</depends>
  <depends>org.jetbrains.plugins.terminal</depends>

  <extensions defaultExtensionNs="com.intellij">
    <projectService serviceImplementation="dev.devdepot.conductor.workspace.WorkspaceService"/>
    <projectService serviceImplementation="dev.devdepot.conductor.ide.ProjectOpener"/>
    <projectService serviceImplementation="dev.devdepot.conductor.settings.ConductorSettings"/>
    <projectConfigurable
        parentId="tools"
        instance="dev.devdepot.conductor.settings.ConductorConfigurable"
        id="dev.devdepot.conductor.settings"
        displayName="Conductor"/>
    <postStartupActivity implementation="dev.devdepot.conductor.startup.ConductorStartupActivity"/>
    <notificationGroup id="Conductor" displayType="BALLOON" toolWindowId="Version Control"/>
  </extensions>

  <actions>
    <group id="Conductor.Menu" text="Conductor" popup="true">
      <add-to-group group-id="VcsGroups" anchor="last"/>
      <action id="Conductor.NewWorkspace"    class="dev.devdepot.conductor.actions.NewWorkspaceAction"    text="New AI Workspace"/>
      <action id="Conductor.ListWorkspaces"  class="dev.devdepot.conductor.actions.ListWorkspacesAction"  text="List AI Workspaces"/>
      <action id="Conductor.FinishWorkspace" class="dev.devdepot.conductor.actions.FinishWorkspaceAction" text="Finish AI Workspace"/>
    </group>
  </actions>
</idea-plugin>
```

---

## README contents (the part the user needs)

Sections in `README.md`:

1. **What Conductor does** — one paragraph: AI Workspace = worktree + IDE window + chained startup terminal tab (`<script> && claude`, or just `claude` if no script). Link to `USER_JOURNEYS.md`.
2. **Prerequisites** — JDK 17, git CLI on `PATH`, `claude` CLI on `PATH` (otherwise the terminal tab opens but the command fails visibly — workspace still usable).
3. **Dev loop**
   - `./gradlew runIde` → opens a *separate* sandboxed IDE; its config lives in `build/idea-sandbox/config/` and is independent of your real IDE.
   - **No hot reload** — code changes require stopping the sandbox and re-running `runIde`.
   - First boot is slow (downloads the IC distribution under `~/.gradle/caches/modules-2/`).
4. **Pointing the sandbox at a test repo**
   - In the sandbox IDE, *File → Open* a throwaway git repo.
   - Recommended: `mkdir -p /tmp/conductor-playground && cd /tmp/conductor-playground && git init && git commit --allow-empty -m init`.
   - Actions appear under *Git → Conductor* (or via `Shift+Shift` → "New AI Workspace").
5. **Logs**
   - `tail -f build/idea-sandbox/system/log/idea.log` while `runIde` is running.
   - In code: `private val log = Logger.getInstance(MyClass::class.java)`. `log.info / log.warn / log.error` land in `idea.log`.
6. **Manual test checklist** (run after every meaningful change; mirrors `USER_JOURNEYS.md`)
   - [ ] `./gradlew runIde` boots without errors in `idea.log`.
   - [ ] **J0** — *Settings → Tools → Conductor* shows three fields (`startupScriptPath`, `worktreeRoot`, `defaultMergeStrategy`); edits persist across sandbox restarts.
   - [ ] **J1** — *Git → Conductor → New AI Workspace* opens dialog; default name matches `wt/<word>-<word>-<hex>`; base dropdown defaults to the detected default branch (origin HEAD, or current branch if HEAD not set). Submit → new IDE window opens at `<worktreeRoot>/<slug>`; **`.conductor/` exists at its root**; original window unchanged; `git worktree list` shows it; terminal tab `<name> · claude` opens and runs the chained `<script> && claude` (or just `claude`); "AI Workspace ready" notification appears.
   - [ ] **J1 edges** — worktree dir already exists → error notification, no window; invalid base branch → error, no partial state; `claude` not on PATH → tab still opens, command fails visibly, workspace otherwise usable.
   - [ ] **J2** — *List AI Workspaces* with 2+ Conductor workspaces shows one row each; non-Conductor worktrees are omitted (or shown dimmed). Invoked from inside a worktree: current row annotated and preselected. Invoked from outside any worktree: no row annotated/preselected. Selecting a row opens/focuses its window. Empty repo shows the "No active AI Workspaces" message.
   - [ ] **J3** — *Finish AI Workspace* enabled only in a worktree window. Dirty worktree → **hard-error notification listing dirty files; dialog does not open**. Clean worktree → dialog opens with strategy radio `ff-only` / `--no-ff` / `rebase` / `squash` / `discard`; default matches `settings.defaultMergeStrategy`. Merge success: target branch receives the commit, worktree window closes, worktree is removed, branch deleted; main repo window's VCS view refreshes to show the new commit without manual reload (main repo window does not need to be open during the merge).
   - [ ] **J3 edges** — merge/rebase conflict → abort + "resolve manually" notification, worktree preserved; `worktree remove` refusal → `--force` follow-up confirm.
   - [ ] **J4** — *Finish → Discard* → confirm dialog mentions lost commits; on confirm branch + worktree disappear, window closes. Discard invocable from main repo window too.
   - [ ] **J5** — Re-opening a previously created worktree project (with `.conductor/` present) opens the `<name> · claude` terminal tab and runs the chained startup command automatically; no first-run popups. Opening a non-Conductor worktree (no `.conductor/`) does nothing — silent no-op.
   - [ ] **J6** — In a non-git directory, *Git → Conductor → New AI Workspace* is disabled; invoking via `Shift+Shift` shows "Conductor requires a git repository."
7. **Building a distributable**
   - `./gradlew buildPlugin` → `build/distributions/conductor-0.1.0.zip`
   - Install in real IDE via *Settings → Plugins → ⚙ → Install Plugin from Disk…*
8. **Troubleshooting**
   - "Plugin not loading" → check `build/idea-sandbox/system/log/idea.log` for `PluginException`.
   - Sandbox state weird → `./gradlew clean` removes `build/idea-sandbox/`.
   - JBR version mismatch → ensure JDK 17 (`./gradlew --version`).

---

## `TODO.md` contents

- Automated tests — JUnit5 for `Git` porcelain parser + `WorkspaceService.create/finish` against temp git repos; `BasePlatformTestCase` for `WorkspaceService` + actions.
- Additional environments: **Local** (in-repo branch, no separate window — deferred because of JetBrains' one-window-per-dir constraint; revisit if that changes) and **Docker** (third option from the original three-env sketch).
- **Submodules** — not handled in v0.1; `.git` file vs directory detection and `git-common-dir` resolution need verification against submoduled repos.
- **"Reopen Claude terminal" action** — when the tab was closed, let the user re-run the chained startup command without restarting the window.
- **Auto-stash on dirty Finish** — v0.1 refuses outright; a future version could offer "stash & continue" in the dirty-check notification.
- `gh` CLI integration for PR creation (currently delegated to the JetBrains GitHub plugin).
- Per-repo config inside `.conductor/` (e.g. `config.yaml`) overriding global settings.
- Keyboard shortcut for *New AI Workspace* (e.g. `Ctrl+Shift+N`).
- Tool window with persistent workspace list + status indicators (clean/dirty/ahead/behind), using `.conductor/` as a place to cache state.
- Plugin Verifier (`./gradlew verifyPlugin`) wired into a CI workflow.
- Windows + submodule path edge cases for `isWorktree` detection.

---

## Critical files to be created

All new — repo currently only contains `PLAN.md`, `USER_JOURNEYS.md`, and `IMPLEMENTATION_PLAN.md`:

- `README.md`, `TODO.md`
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`
- `src/main/resources/META-INF/plugin.xml`
- `src/main/kotlin/dev/devdepot/conductor/workspace/{Workspace,WorkspaceService,ConductorMarker}.kt`
- `src/main/kotlin/dev/devdepot/conductor/git/Git.kt`
- `src/main/kotlin/dev/devdepot/conductor/actions/{NewWorkspaceAction,ListWorkspacesAction,FinishWorkspaceAction}.kt`
- `src/main/kotlin/dev/devdepot/conductor/ui/{NewWorkspaceDialog,FinishWorkspaceDialog}.kt`
- `src/main/kotlin/dev/devdepot/conductor/ide/{ProjectOpener,ClaudeTerminalLauncher}.kt`
- `src/main/kotlin/dev/devdepot/conductor/settings/{ConductorSettings,ConductorConfigurable}.kt`
- `src/main/kotlin/dev/devdepot/conductor/startup/ConductorStartupActivity.kt`

No existing functions/utilities to reuse — greenfield.

---

## Verification

End-to-end check after Phase 6:

1. `./gradlew runIde` boots the sandbox IDE.
2. In the sandbox, open `/tmp/conductor-playground` (a fresh git repo with at least one commit).
3. Walk through every item in the README's "Manual test checklist" section (each item maps to a journey in `USER_JOURNEYS.md`, J0–J6).
4. `tail -f build/idea-sandbox/system/log/idea.log` in another terminal — no `ERROR` or `WARN` lines from `dev.devdepot.conductor.*` during the checklist.
5. `./gradlew buildPlugin` produces `build/distributions/conductor-0.1.0.zip`; install it into your real JetBrains IDE and re-run a smoke test (New AI Workspace → work in the new window → Finish → Merge).
