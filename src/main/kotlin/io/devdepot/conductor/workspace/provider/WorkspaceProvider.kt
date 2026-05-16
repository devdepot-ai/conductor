package io.devdepot.conductor.workspace.provider

import com.intellij.openapi.project.Project
import io.devdepot.conductor.settings.MergeStrategy
import io.devdepot.conductor.workspace.FinishLog
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService
import java.nio.file.Path

/**
 * A WorkspaceProvider is the implementation behind the logical [Workspace]
 * concept. Today there is a single provider ([WorktreeWorkspaceProvider]) —
 * the interface exists to mark the seam where future backings (Docker,
 * remote, local-copy) would plug in.
 *
 * Contract: every provider MUST write [io.devdepot.conductor.workspace.ConductorMarker.MARKER_FILE]
 * at the workspace root when creating a workspace. The marker file is the
 * universal identity gate used by [io.devdepot.conductor.actions.ActionContext]
 * and the VFS listener — without it, a provider's workspaces will not show
 * up in the toolbar, lists, or startup activity.
 */
interface WorkspaceProvider {
    /** Stable identifier, e.g. `"worktree"`. */
    val id: String

    /**
     * Enumerate all workspaces this provider knows about for [project]'s repo.
     * Called from a background thread (never from ReadAction).
     */
    fun enumerate(project: Project, trunk: Path): List<Workspace>

    fun create(project: Project, spec: CreateSpec): WorkspaceService.Result

    fun finish(
        project: Project,
        workspace: Workspace,
        strategy: MergeStrategy,
        baseBranch: String,
        deleteBranch: Boolean,
        log: FinishLog? = null,
    ): WorkspaceService.FinishResult

    fun discard(project: Project, workspace: Workspace): WorkspaceService.FinishResult

    /**
     * Teardown without a merge: close the IDE window, remove the worktree,
     * optionally delete the branch, refresh VCS. Used by the PR watcher when
     * a tracked PR merges remotely.
     */
    fun reap(
        project: Project,
        workspace: Workspace,
        deleteBranch: Boolean,
    ): WorkspaceService.FinishResult
}

data class CreateSpec(
    val branchName: String,
    val baseBranch: String,
    val slug: String,
    val skipStartupCommand: Boolean = false,
)
