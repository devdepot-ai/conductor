package io.devdepot.conductor.toolwindow.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import io.devdepot.conductor.icons.ConductorIcons
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService

/**
 * Panel-local delete action. Confirms, then runs [WorkspaceService.discard]
 * (force-remove worktree + branch + close window + invalidate). Wording
 * mirrors [io.devdepot.conductor.actions.FinishWorkspaceAction]'s discard path
 * so the two entry points feel identical.
 */
class DeleteWorkspaceAction(
    private val workspaceSupplier: () -> Workspace?,
) : AnAction("Delete", "Discard this workspace and its branch", ConductorIcons.Delete), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && workspaceSupplier() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workspace = workspaceSupplier() ?: return

        val ok = Messages.showYesNoDialog(
            project,
            "Discard `${workspace.branch}`? This deletes the branch and workspace. Unmerged commits will be lost.",
            "Discard AI Workspace",
            Messages.getWarningIcon(),
        )
        if (ok != Messages.YES) return

        val service = WorkspaceService.get(project)
        object : Task.Backgroundable(project, "Discarding AI Workspace", false) {
            override fun run(indicator: ProgressIndicator) {
                when (val r = service.discard(workspace)) {
                    is WorkspaceService.FinishResult.Ok -> Notifications.info(project, "Conductor", r.message)
                    is WorkspaceService.FinishResult.Error -> Notifications.error(project, "Conductor", r.message)
                    else -> {}
                }
            }
        }.queue()
    }
}
