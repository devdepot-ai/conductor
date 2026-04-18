package io.devdepot.conductor.actions

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.ide.ProjectOpener
import io.devdepot.conductor.workspace.Workspace
import java.nio.file.Files

/**
 * Focus an already-open IDE window for the workspace, or open a new one.
 * If the worktree directory has been removed underneath us, offer to clean
 * up the stale worktree record. Must be called from the EDT.
 */
internal fun openWorkspace(project: Project, workspace: Workspace) {
    val opener = project.getService(ProjectOpener::class.java)
    val existing = opener.focusIfOpen(workspace.path)
    if (existing != null) {
        WindowManager.getInstance().getFrame(existing)?.toFront()
        return
    }
    if (!Files.exists(workspace.path)) {
        val stale = Messages.showYesNoDialog(
            project,
            "Workspace directory ${workspace.path} is missing. Remove stale worktree record?",
            "Stale Workspace",
            Messages.getWarningIcon(),
        )
        if (stale == Messages.YES) {
            object : Task.Backgroundable(project, "Removing stale worktree…", false) {
                override fun run(indicator: ProgressIndicator) {
                    val repo = Git.mainRepoRoot(workspace.path) ?: return
                    Git.worktreeRemove(repo, workspace.path, force = true)
                }
            }.queue()
        }
        return
    }
    opener.openInNewWindow(workspace.path)
}
