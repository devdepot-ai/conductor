package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import io.devdepot.conductor.icons.ConductorIcons
import io.devdepot.conductor.workspace.WorkspaceService

/**
 * Main-toolbar button.
 *
 * Label and click target flip based on state:
 *   - Trunk window      → Create AI Workspace
 *   - Workspace window  → Finish AI Workspace (label shows the branch)
 *
 * Listing and switching live in the Conductor tool window.
 */
class WorkspaceToolbarAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val presentation = e.presentation
        val trunk = project != null && ActionContext.isTrunk(project)
        val workspace = project != null && ActionContext.isWorkspace(project)

        if (!trunk && !workspace) {
            presentation.isEnabledAndVisible = false
            return
        }

        val current = if (workspace) WorkspaceService.get(project!!).cachedSnapshot().current else null
        if (current != null) {
            presentation.text = current.branch
            presentation.icon = ConductorIcons.Finish
            presentation.description = "Finish AI Workspace"
        } else {
            presentation.text = "AI Workspace"
            presentation.icon = ConductorIcons.Create
            presentation.description = "Create a new AI Workspace"
        }
        presentation.isEnabledAndVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val actionId = if (ActionContext.isWorkspace(project)) {
            "Conductor.FinishWorkspace"
        } else {
            "Conductor.NewWorkspace"
        }
        ActionManager.getInstance().getAction(actionId)?.actionPerformed(e)
    }
}
