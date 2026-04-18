package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.ui.NewWorkspaceDialog
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.WorkspaceService

class NewWorkspaceAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null && ActionContext.isTrunk(project)
        e.presentation.isEnabled = enabled
        e.presentation.description = if (enabled) {
            "Create a new AI workspace (dedicated IDE window + claude)."
        } else {
            "Not available from inside an AI Workspace."
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = WorkspaceService.get(project)
        val settings = ConductorSettings.get(project)

        object : Task.Backgroundable(project, "Preparing AI Workspace…", false) {
            override fun run(indicator: ProgressIndicator) {
                val trunk = service.trunk() ?: run {
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.error(
                            project,
                            "Conductor",
                            "Could not locate the trunk repository.",
                        )
                    }
                    return
                }
                val defaultBase = Git.detectDefaultBranch(trunk)
                val branches = Git.listLocalBranches(trunk)
                val defaultName = Git.generateBranchName(settings.branchPrefix)

                ApplicationManager.getApplication().invokeLater {
                    showDialogAndCreate(project, service, defaultName, defaultBase, branches)
                }
            }
        }.queue()
    }

    private fun showDialogAndCreate(
        project: Project,
        service: WorkspaceService,
        defaultName: String,
        defaultBase: String,
        branches: List<String>,
    ) {
        val dialog = NewWorkspaceDialog(project, defaultName, defaultBase, branches)
        if (!dialog.showAndGet()) return

        val branchName = dialog.name.trim()
        val baseBranch = dialog.baseBranch
        val slug = branchName.substringAfter('/', branchName)

        object : Task.Backgroundable(project, "Creating AI Workspace", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "git worktree add -b $branchName"
                when (val r = service.create(branchName, baseBranch, slug)) {
                    is WorkspaceService.Result.Ok -> {
                        Notifications.info(
                            project,
                            "Conductor",
                            "AI Workspace `${r.workspace.branch}` ready.",
                        )
                    }
                    is WorkspaceService.Result.Error -> {
                        Notifications.error(project, "Conductor", r.message)
                    }
                }
            }
        }.queue()
    }
}
