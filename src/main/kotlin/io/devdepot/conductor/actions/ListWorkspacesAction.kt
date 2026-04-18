package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.ide.ProjectOpener
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService
import java.nio.file.Files

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
        val workspaces = service.list()
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
                return "${w.branch}    ${w.path}$suffix"
            }

            override fun getDefaultOptionIndex(): Int =
                workspaces.indexOfFirst { it.isCurrent }.coerceAtLeast(0)

            override fun onChosen(selected: Workspace?, finalChoice: Boolean): PopupStep<*>? {
                if (selected != null && finalChoice) {
                    ApplicationManager.getApplication().invokeLater { onSelected(project, selected) }
                }
                return FINAL_CHOICE
            }
        }

        JBPopupFactory.getInstance()
            .createListPopup(step)
            .showCenteredInCurrentWindow(project)
    }

    private fun onSelected(
        project: com.intellij.openapi.project.Project,
        w: Workspace,
    ) {
        val opener = project.getService(ProjectOpener::class.java)
        val existing = opener.focusIfOpen(w.path)
        if (existing != null) {
            com.intellij.openapi.wm.WindowManager.getInstance().getFrame(existing)?.toFront()
            return
        }
        if (!Files.exists(w.path)) {
            val stale = Messages.showYesNoDialog(
                project,
                "Worktree directory ${w.path} is missing. Remove stale worktree record?",
                "Stale Worktree",
                Messages.getWarningIcon(),
            )
            if (stale == Messages.YES) {
                val repo = Git.mainRepoRoot(w.path) ?: return
                Git.worktreeRemove(repo, w.path, force = true)
            }
            return
        }
        opener.openInNewWindow(w.path)
    }
}
