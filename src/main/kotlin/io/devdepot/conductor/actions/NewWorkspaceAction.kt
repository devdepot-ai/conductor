package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.ui.NewWorkspaceDialog
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.WorkspaceService
import java.nio.file.Path

class NewWorkspaceAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null && isGitDir(project)
        e.presentation.isEnabled = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!isGitDir(project)) {
            Notifications.warn(project, "Conductor", "Conductor requires a git repository.")
            return
        }
        val service = WorkspaceService.get(project)
        val settings = ConductorSettings.get(project)
        val mainRepo = service.mainRepo() ?: run {
            Notifications.error(project, "Conductor", "Could not locate main repository.")
            return
        }

        val defaultBase = Git.detectDefaultBranch(mainRepo)
        val branches = Git.listLocalBranches(mainRepo)
        val defaultName = Git.generateBranchName(settings.branchPrefix)

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

    private fun isGitDir(project: Project): Boolean {
        val base = project.basePath ?: return false
        return Git.isGitRepo(Path.of(base))
    }
}
