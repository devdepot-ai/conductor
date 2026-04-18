package io.devdepot.conductor.toolwindow.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import io.devdepot.conductor.icons.ConductorIcons
import io.devdepot.conductor.workspace.WorkspaceService

/**
 * Panel-local refresh: invalidates the snapshot cache. The service broadcasts
 * on [io.devdepot.conductor.workspace.WorkspaceTopics.CHANGED] once the
 * background refresh finishes, which drives the panel reload.
 */
class RefreshWorkspacesAction :
    AnAction("Refresh", "Refresh the workspace list", ConductorIcons.Refresh), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        WorkspaceService.get(project).invalidate()
    }
}
