package io.devdepot.conductor.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.settings.MergeStrategy
import io.devdepot.conductor.workspace.provider.CreateSpec
import io.devdepot.conductor.workspace.provider.WorkspaceProvider
import io.devdepot.conductor.workspace.provider.WorktreeWorkspaceProvider
import java.nio.file.Path
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
    }

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
                val next = computeSnapshot()
                snapshot = next
                snapshotAtMs = System.currentTimeMillis()
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

    fun finish(
        workspace: Workspace,
        strategy: MergeStrategy,
        baseBranch: String,
        deleteBranch: Boolean,
    ): FinishResult {
        val result = provider.finish(project, workspace, strategy, baseBranch, deleteBranch)
        if (result is FinishResult.Ok) invalidate()
        return result
    }

    fun discard(workspace: Workspace): FinishResult {
        val result = provider.discard(project, workspace)
        if (result is FinishResult.Ok) invalidate()
        return result
    }

    companion object {
        private const val CACHE_TTL_MS = 1500L

        fun get(project: Project): WorkspaceService = project.service()
    }
}
