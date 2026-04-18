package io.devdepot.conductor.actions

import com.intellij.openapi.project.Project
import io.devdepot.conductor.workspace.ConductorMarker
import java.nio.file.Files
import java.nio.file.Path

/**
 * `trunk` and `workspace` are Conductor concepts — a workspace happens to be
 * backed by a git worktree today, but the plugin's menu/UX talks about
 * workspaces, not worktrees.
 *
 * All checks here are filesystem-only. Running `git` subprocesses from an
 * action's `update(e)` is forbidden: IntelliJ wraps updates in a ReadAction
 * and blocking process execution under a ReadAction throws.
 *   - `.git` is a directory  → trunk
 *   - `.git` is a regular file → linked git worktree (points at the common dir)
 *   - marker file present    → Conductor workspace
 */
internal object ActionContext {
    fun isTrunk(project: Project): Boolean {
        val path = project.basePath?.let { Path.of(it) } ?: return false
        val dotGit = path.resolve(".git")
        if (!Files.isDirectory(dotGit)) return false
        return !ConductorMarker.isWorkspace(path)
    }

    fun isWorkspace(project: Project): Boolean {
        val path = project.basePath?.let { Path.of(it) } ?: return false
        return ConductorMarker.isWorkspace(path)
    }
}
