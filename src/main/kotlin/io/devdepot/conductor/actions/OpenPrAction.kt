package io.devdepot.conductor.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.ConductorMarker
import io.devdepot.conductor.workspace.WorkspaceService

/**
 * Opens the PR URL stored in the current workspace's marker. Enabled only
 * when the workspace has a tracked PR (any state).
 */
class OpenPrAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val current = project?.let { WorkspaceService.get(it).cachedSnapshot().current }
        val url = current?.let { ConductorMarker.readConfig(it.location)?.pr?.url }
        e.presentation.isEnabledAndVisible = url != null
        e.presentation.description = if (url != null) "Open PR in browser" else "No PR tracked for this workspace."
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workspace = WorkspaceService.get(project).current() ?: return
        val url = ConductorMarker.readConfig(workspace.location)?.pr?.url ?: run {
            Notifications.warn(project, "Conductor", "No PR URL recorded for `${workspace.branch}`.")
            return
        }
        BrowserUtil.browse(url)
    }
}
