package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService

class ListWorkspacesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null && ActionContext.isTrunk(project)
        e.presentation.isEnabled = enabled
        e.presentation.description = if (enabled) {
            "Show all active AI workspaces and switch between them."
        } else {
            "Not available from inside an AI Workspace."
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = WorkspaceService.get(project)

        object : Task.Backgroundable(project, "Loading AI Workspaces…", false) {
            override fun run(indicator: ProgressIndicator) {
                val workspaces = service.list()
                ApplicationManager.getApplication().invokeLater {
                    showPopup(project, workspaces)
                }
            }
        }.queue()
    }

    private fun showPopup(project: Project, workspaces: List<Workspace>) {
        if (workspaces.isEmpty()) {
            Notifications.info(
                project,
                "Conductor",
                "No active AI Workspaces. Create one with New AI Workspace.",
            )
            return
        }

        val step = object : BaseListPopupStep<Workspace>("AI Workspaces", workspaces) {
            override fun getTextFor(w: Workspace): String {
                val suffix = if (w.isCurrent) "    ← current" else ""
                return "${w.branch}    ${w.location}$suffix"
            }

            override fun getDefaultOptionIndex(): Int =
                workspaces.indexOfFirst { it.isCurrent }.coerceAtLeast(0)

            override fun onChosen(selected: Workspace?, finalChoice: Boolean): PopupStep<*>? {
                if (selected != null && finalChoice) {
                    ApplicationManager.getApplication().invokeLater { openWorkspace(project, selected) }
                }
                return FINAL_CHOICE
            }
        }

        JBPopupFactory.getInstance()
            .createListPopup(step)
            .showCenteredInCurrentWindow(project)
    }
}
