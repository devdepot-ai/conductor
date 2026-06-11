package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class NewWorkspaceAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null && ActionContext.isTrunk(project)
        e.presentation.isEnabled = enabled
        e.presentation.description = if (enabled) {
            "Create a new AI workspace (git worktree + dedicated IDE window)."
        } else {
            "Not available from inside an AI Workspace."
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NewWorkspaceLauncher.launch(project)
    }
}
