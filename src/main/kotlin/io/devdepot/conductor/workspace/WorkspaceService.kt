package io.devdepot.conductor.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.devdepot.conductor.forge.ForgeDetector
import io.devdepot.conductor.forge.PrClientFactory
import io.devdepot.conductor.forge.PrOutcome
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.settings.MergeStrategy
import io.devdepot.conductor.startup.FinishCommandRunner
import io.devdepot.conductor.workspace.provider.CreateSpec
import io.devdepot.conductor.workspace.provider.WorkspaceProvider
import io.devdepot.conductor.workspace.provider.WorktreeWorkspaceProvider
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates workspace lifecycle and caches the enumeration snapshot.
 *
 * The actual workspace implementation lives in a [WorkspaceProvider]. Today
 * that's a single [WorktreeWorkspaceProvider]; future providers (Docker,
 * remote, local-copy) would be dispatched from here.
 */
@Service(Service.Level.PROJECT)
class WorkspaceService(private val project: Project) {

    private val log = Logger.getInstance(WorkspaceService::class.java)

    private val provider: WorkspaceProvider = WorktreeWorkspaceProvider()

    sealed class Result {
        data class Ok(val workspace: Workspace) : Result()
        data class Error(val message: String) : Result()
    }

    sealed class FinishResult {
        data class Ok(val message: String) : FinishResult()
        data class Dirty(val files: List<String>) : FinishResult()
        data class Conflict(val message: String) : FinishResult()
        data class Error(val message: String) : FinishResult()

        /**
         * A PR was opened on the forge. If [awaitingMerge] is true the
         * workspace is preserved and the watcher will reap it on merge;
         * otherwise a local merge also completed (the workspace is already
         * gone by the time the caller sees this variant).
         */
        data class PrOpened(
            val url: String,
            val number: Int,
            val awaitingMerge: Boolean,
        ) : FinishResult()

        data class PrCreateFailed(val message: String) : FinishResult()
    }

    data class FinishRequest(
        val workspace: Workspace,
        val runFinishCommand: Boolean,
        val openPr: Boolean,
        val prTitle: String,
        val prBody: String,
        val mergeLocally: Boolean,
        val baseBranch: String,
        val deleteBranch: Boolean,
        val strategy: MergeStrategy,
    )

    data class Snapshot(
        val trunk: Path?,
        val workspaces: List<Workspace>,
        val current: Workspace?,
    ) {
        companion object {
            val EMPTY = Snapshot(null, emptyList(), null)
        }
    }

    @Volatile private var snapshot: Snapshot = Snapshot.EMPTY
    @Volatile private var snapshotAtMs: Long = 0L
    private val refreshing = AtomicBoolean(false)

    fun projectPath(): Path? = project.basePath?.let { Path.of(it) }

    fun trunk(): Path? {
        val p = projectPath() ?: return null
        if (!Git.isGitRepo(p)) return null
        return if (Git.isWorktree(p)) Git.mainRepoRoot(p) else p
    }

    /**
     * All Conductor workspaces for the current repo. Excludes the trunk itself.
     */
    fun list(): List<Workspace> = computeSnapshot().workspaces

    fun current(): Workspace? = computeSnapshot().current

    /**
     * Cache-only view for callers running under a ReadAction (e.g. action
     * `update`/`getChildren`), where spawning `git` synchronously would throw.
     * Returns stale data if the cache is cold; schedules an async refresh.
     */
    fun cachedSnapshot(): Snapshot {
        val now = System.currentTimeMillis()
        if (now - snapshotAtMs > CACHE_TTL_MS) scheduleRefresh()
        return snapshot
    }

    /**
     * Drop the cached snapshot and kick off a refresh. Call after mutations
     * (create/finish/discard) so the next menu open reflects the new state.
     */
    fun invalidate() {
        snapshotAtMs = 0L
        scheduleRefresh()
    }

    /**
     * Eagerly populate the cache from a safe thread (e.g. startup activity).
     */
    fun warmCache() {
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        if (!refreshing.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val previous = snapshot
                val next = computeSnapshot()
                snapshot = next
                snapshotAtMs = System.currentTimeMillis()
                if (next != previous && !project.isDisposed) {
                    project.messageBus.syncPublisher(WorkspaceTopics.CHANGED).changed()
                }
            } catch (t: Throwable) {
                log.warn("WorkspaceService snapshot refresh failed", t)
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun computeSnapshot(): Snapshot {
        val trunkPath = trunk() ?: return Snapshot.EMPTY
        val workspaces = provider.enumerate(project, trunkPath)
        val currentProjectPath = projectPath()?.toAbsolutePath()?.normalize()
        val current = if (currentProjectPath != null && ConductorMarker.isWorkspace(currentProjectPath)) {
            workspaces.firstOrNull { it.location.toAbsolutePath().normalize() == currentProjectPath }
        } else null
        return Snapshot(trunkPath, workspaces, current)
    }

    fun defaultWorktreeRoot(): Path {
        val repo = trunk() ?: projectPath() ?: Path.of(System.getProperty("user.home"))
        val parent = repo.parent ?: repo
        val repoName = repo.fileName?.toString() ?: "repo"
        return parent.resolve("$repoName-worktrees")
    }

    fun resolveWorktreeRoot(): Path {
        val configured = ConductorSettings.get(project).worktreeRoot.trim()
        return if (configured.isEmpty()) defaultWorktreeRoot() else Path.of(configured)
    }

    fun create(branchName: String, baseBranch: String, slug: String): Result {
        val result = provider.create(project, CreateSpec(branchName, baseBranch, slug))
        if (result is Result.Ok) invalidate()
        return result
    }

    fun finish(req: FinishRequest): FinishResult {
        val workspace = req.workspace
        if (Git.isDirty(workspace.location)) {
            val files = Git.statusPorcelain(workspace.location).stdout.lines()
                .filter { it.isNotBlank() }
            return FinishResult.Dirty(files)
        }

        val settings = ConductorSettings.get(project)
        if (req.runFinishCommand && settings.finishCommand.isNotBlank()) {
            val exit = runFinishCommandSync(
                cwd = workspace.location,
                command = settings.finishCommand,
                tabTitle = "Conductor finish: ${workspace.branch}",
            )
            if (exit != 0) {
                return FinishResult.Error(
                    "Finish command exited with code $exit — workspace preserved. " +
                        "See the Run tool window for output.",
                )
            }
        }

        val finishLog = FinishLog(project, "Conductor finish: ${workspace.branch}")
        try {
            var prOutcome: FinishResult? = null
            if (req.openPr) {
                finishLog.section("Push branch ${workspace.branch}")
                val push = Git.push(workspace.location, branch = workspace.branch)
                finishLog.command(
                    "git push --set-upstream origin ${workspace.branch}",
                    push,
                )
                if (!push.ok) {
                    val msg = push.stderr.ifBlank { push.stdout }
                        .ifBlank { "git push failed (exit ${push.exitCode})." }
                    finishLog.error("Push failed; skipping PR creation.")
                    return FinishResult.PrCreateFailed(
                        "Failed to push `${workspace.branch}` to origin:\n$msg",
                    )
                }

                finishLog.section("Open pull request")
                prOutcome = openPullRequest(workspace, req.prTitle, req.prBody, finishLog)
                when (prOutcome) {
                    is FinishResult.PrCreateFailed -> return prOutcome
                    is FinishResult.PrOpened, null -> Unit
                    else -> return prOutcome
                }
            }

            if (req.mergeLocally) {
                finishLog.section("Merge `${workspace.branch}` onto `${req.baseBranch}`")
                val mergeResult = provider.finish(
                    project,
                    workspace,
                    req.strategy,
                    req.baseBranch,
                    req.deleteBranch,
                    finishLog,
                )
                if (mergeResult is FinishResult.Ok) invalidate()
                return mergeResult
            }

            return prOutcome ?: FinishResult.Ok(
                "Workspace `${workspace.branch}` preserved.",
            )
        } finally {
            finishLog.done()
        }
    }

    private fun openPullRequest(
        workspace: Workspace,
        title: String,
        body: String,
        finishLog: FinishLog,
    ): FinishResult {
        val trunkPath = trunk() ?: return FinishResult.PrCreateFailed(
            "Could not locate trunk repository to determine forge.",
        )
        val forge = ForgeDetector.detect(trunkPath)
        val client = PrClientFactory.forForge(project, forge)
            ?: return FinishResult.PrCreateFailed(
                "No forge detected for origin remote — cannot open PR.",
            )
        val base = Git.detectDefaultBranch(trunkPath)
        val resolvedTitle = title.ifBlank { workspace.branch }
        finishLog.info(
            "Creating ${forge.id} PR: ${workspace.branch} -> $base (title: \"$resolvedTitle\")",
        )
        return when (val r = client.create(
            cwd = workspace.location,
            head = workspace.branch,
            base = base,
            title = resolvedTitle,
            body = body,
        )) {
            is PrOutcome.Ok -> {
                finishLog.info("Opened PR #${r.value.number}: ${r.value.url}")
                val now = Instant.now().toString()
                val state = ConductorMarker.PrState(
                    forge = forge.id,
                    number = r.value.number,
                    url = r.value.url,
                    baseBranch = base,
                    headBranch = workspace.branch,
                    state = "open",
                    lastCheckedAt = now,
                    mergedAt = null,
                )
                try {
                    ConductorMarker.writePrState(workspace.location, state)
                } catch (e: Throwable) {
                    log.warn("Failed to write PR state to marker", e)
                }
                invalidate()
                FinishResult.PrOpened(url = r.value.url, number = r.value.number, awaitingMerge = true)
            }
            is PrOutcome.Err -> {
                finishLog.error(r.message)
                FinishResult.PrCreateFailed(r.message)
            }
        }
    }

    private fun runFinishCommandSync(cwd: Path, command: String, tabTitle: String): Int {
        val latch = java.util.concurrent.CountDownLatch(1)
        val exit = java.util.concurrent.atomic.AtomicInteger(-1)
        FinishCommandRunner.run(project, cwd, command, tabTitle) { code ->
            exit.set(code)
            latch.countDown()
        }
        latch.await()
        return exit.get()
    }

    fun discard(workspace: Workspace): FinishResult {
        val result = provider.discard(project, workspace)
        if (result is FinishResult.Ok) invalidate()
        return result
    }

    /**
     * Teardown without merging. Used by the PR watcher when a tracked PR
     * merges remotely and auto-reap is enabled.
     */
    fun reap(workspace: Workspace, deleteBranch: Boolean): FinishResult {
        val result = provider.reap(project, workspace, deleteBranch)
        if (result is FinishResult.Ok) invalidate()
        return result
    }

    /**
     * Rewrite the workspace's marker file with [newName] stored in the
     * `name` field. The marker is treated as the source of truth for the
     * display name, so the next snapshot refresh reflects the change.
     */
    fun rename(workspace: Workspace, newName: String): Result {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return Result.Error("Workspace name cannot be blank.")
        if (trimmed == workspace.name) return Result.Ok(workspace)
        val location = workspace.location
        val existing = ConductorMarker.readConfig(location)
            ?: return Result.Error("Workspace marker not found at $location.")
        return try {
            ConductorMarker.writeConfig(location, existing.copy(name = trimmed))
            invalidate()
            Result.Ok(workspace)
        } catch (e: Exception) {
            Result.Error("Failed to rename workspace: ${e.message}")
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 1500L

        fun get(project: Project): WorkspaceService = project.service()
    }
}
