package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import io.devdepot.conductor.icons.ConductorIcons
import io.devdepot.conductor.workspace.WorkspaceService

/**
 * Main-toolbar split button.
 *
 * Primary action flips based on state:
 *   - Trunk window      → Create AI Workspace
 *   - Workspace window  → Finish AI Workspace (label shows the branch)
 *
 * The arrow reveals: primary action, active workspaces, Settings.
 */
class WorkspaceToolbarAction : ActionGroup(), DumbAware {

    init {
        isPopup = true
        templatePresentation.isPerformGroup = true
    }

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
            presentation.icon = ConductorIcons.InWorkspace
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

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val mgr = ActionManager.getInstance()
        val snap = WorkspaceService.get(project).cachedSnapshot()
        val current = snap.current
        val children = mutableListOf<AnAction>()

        val primaryId = if (current != null) "Conductor.FinishWorkspace" else "Conductor.NewWorkspace"
        mgr.getAction(primaryId)?.let { children += it }

        val others = snap.workspaces.filter { it.path != current?.path }
        if (others.isNotEmpty()) {
            children += Separator.getInstance()
            others.forEach { ws -> children += SwitchToWorkspaceAction(ws) }
        }

        children += Separator.getInstance()
        mgr.getAction("Conductor.OpenSettings")?.let { children += it }
        return children.toTypedArray()
    }
}
