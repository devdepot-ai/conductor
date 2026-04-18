package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import io.devdepot.conductor.icons.ConductorIcons
import io.devdepot.conductor.workspace.Workspace

/**
 * Dropdown entry that opens or focuses another AI Workspace's IDE window.
 * Instantiated per workspace by [WorkspaceToolbarAction.getChildren].
 */
class SwitchToWorkspaceAction(private val workspace: Workspace) :
    AnAction(workspace.branch, workspace.path.toString(), ConductorIcons.InWorkspace), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openWorkspace(project, workspace)
    }
}
