package io.devdepot.conductor.actions

import com.intellij.openapi.project.Project
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.workspace.ConductorMarker
import java.nio.file.Path

/**
 * `trunk` and `workspace` are Conductor concepts — a workspace happens to be
 * backed by a git worktree today, but the plugin's menu/UX talks about
 * workspaces, not worktrees.
 */
internal object ActionContext {
    fun isTrunk(project: Project): Boolean {
        val path = project.basePath?.let { Path.of(it) } ?: return false
        if (!Git.isGitRepo(path)) return false
        if (ConductorMarker.isWorkspace(path)) return false
        return !Git.isWorktree(path)
    }

    fun isWorkspace(project: Project): Boolean {
        val path = project.basePath?.let { Path.of(it) } ?: return false
        return ConductorMarker.isWorkspace(path)
    }
}
