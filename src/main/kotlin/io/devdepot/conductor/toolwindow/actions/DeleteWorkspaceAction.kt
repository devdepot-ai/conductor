package io.devdepot.conductor.toolwindow.actions

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService

/**
 * Confirm-and-discard workspace deletion. Shared by the tool window row
 * trash icon and any future entry points. Wording matches the discard path
 * in [io.devdepot.conductor.actions.FinishWorkspaceAction] so both entry
 * points feel identical.
 */
internal fun confirmAndDiscardWorkspace(project: Project, workspace: Workspace) {
    confirmAndDiscardWorkspaces(project, listOf(workspace))
}

internal fun confirmAndDiscardWorkspaces(project: Project, workspaces: List<Workspace>) {
    if (workspaces.isEmpty()) return
    val message = if (workspaces.size == 1) {
        "Discard `${workspaces.single().branch}`? This deletes the branch and workspace. Unmerged commits will be lost."
    } else {
        val list = workspaces.joinToString("\n") { "  • ${it.branch}" }
        "Discard ${workspaces.size} workspaces? This deletes their branches and worktrees. Unmerged commits will be lost.\n\n$list"
    }
    val ok = Messages.showYesNoDialog(
        project,
        message,
        if (workspaces.size == 1) "Discard AI Workspace" else "Discard AI Workspaces",
        Messages.getWarningIcon(),
    )
    if (ok != Messages.YES) return

    val service = WorkspaceService.get(project)
    object : Task.Backgroundable(project, "Discarding AI Workspaces", false) {
        override fun run(indicator: ProgressIndicator) {
            workspaces.forEach { ws ->
                indicator.text = "Discarding ${ws.branch}"
                when (val r = service.discard(ws)) {
                    is WorkspaceService.FinishResult.Ok -> Notifications.info(project, "Conductor", r.message)
                    is WorkspaceService.FinishResult.Error -> Notifications.error(project, "Conductor", r.message)
                    else -> {}
                }
            }
        }
    }.queue()
}
