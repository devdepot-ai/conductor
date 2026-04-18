package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.settings.MergeStrategy
import io.devdepot.conductor.startup.FinishCommandRunner
import io.devdepot.conductor.ui.FinishWorkspaceDialog
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.ConductorMarker
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService

class FinishWorkspaceAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null && ActionContext.isWorkspace(project)
        e.presentation.isEnabled = enabled
        e.presentation.description = if (enabled) {
            "Merge, rebase, squash, or discard the current AI workspace."
        } else {
            "Only available from inside an AI Workspace."
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = WorkspaceService.get(project)
        val settings = ConductorSettings.get(project)

        object : Task.Backgroundable(project, "Preparing to finish workspace…", false) {
            override fun run(indicator: ProgressIndicator) {
                val workspace = service.current() ?: run {
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.warn(
                            project,
                            "Conductor",
                            "Finish AI Workspace must be run from inside a Conductor workspace.",
                        )
                    }
                    return
                }

                if (Git.isDirty(workspace.path)) {
                    val files = Git.statusPorcelain(workspace.path).stdout.lines()
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.error(
                            project,
                            "Conductor — workspace is dirty",
                            "Commit or stash first. Changed files:\n$files",
                        )
                    }
                    return
                }

                val repo = Git.mainRepoRoot(workspace.path) ?: run {
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.error(project, "Conductor", "Could not locate trunk repository.")
                    }
                    return
                }
                val defaultBase = Git.detectDefaultBranch(repo)
                val branches = Git.listLocalBranches(repo).ifEmpty { listOf(defaultBase) }

                val snapshot = ConductorMarker.readConfig(workspace.path)
                val defaultStrategy = snapshot?.defaultMergeStrategy
                    ?.let { MergeStrategy.fromId(it) }
                    ?: settings.defaultMergeStrategy

                ApplicationManager.getApplication().invokeLater {
                    showDialogAndFinish(project, service, workspace, defaultBase, branches, defaultStrategy)
                }
            }
        }.queue()
    }

    private fun showDialogAndFinish(
        project: Project,
        service: WorkspaceService,
        workspace: Workspace,
        defaultBase: String,
        branches: List<String>,
        defaultStrategy: MergeStrategy,
    ) {
        val dialog = FinishWorkspaceDialog(
            project = project,
            branch = workspace.branch,
            defaultBase = defaultBase,
            branches = branches,
            defaultStrategy = defaultStrategy,
        )
        if (!dialog.showAndGet()) return

        if (dialog.discard) {
            val ok = Messages.showYesNoDialog(
                project,
                "Discard `${workspace.branch}`? This deletes the branch and workspace. Unmerged commits will be lost.",
                "Discard AI Workspace",
                Messages.getWarningIcon(),
            )
            if (ok != Messages.YES) return

            object : Task.Backgroundable(project, "Discarding AI Workspace", false) {
                override fun run(indicator: ProgressIndicator) {
                    when (val r = service.discard(workspace)) {
                        is WorkspaceService.FinishResult.Ok -> Notifications.info(project, "Conductor", r.message)
                        is WorkspaceService.FinishResult.Error -> Notifications.error(project, "Conductor", r.message)
                        else -> {}
                    }
                }
            }.queue()
            return
        }

        val strategy = dialog.strategy
        val baseBranch = dialog.baseBranch
        val deleteBranch = dialog.deleteBranch
        val finishCommand = ConductorSettings.get(project).finishCommand

        if (finishCommand.isBlank()) {
            queueMerge(project, service, workspace, strategy, baseBranch, deleteBranch)
            return
        }

        FinishCommandRunner.run(
            project = project,
            cwd = workspace.path,
            command = finishCommand,
            tabTitle = "Conductor finish: ${workspace.branch}",
        ) { exit ->
            if (exit == 0) {
                queueMerge(project, service, workspace, strategy, baseBranch, deleteBranch)
            } else {
                Notifications.error(
                    project,
                    "Conductor",
                    "Finish command exited with code $exit — workspace preserved. " +
                        "See the Run tool window for output.",
                )
            }
        }
    }

    private fun queueMerge(
        project: Project,
        service: WorkspaceService,
        workspace: Workspace,
        strategy: MergeStrategy,
        baseBranch: String,
        deleteBranch: Boolean,
    ) {
        object : Task.Backgroundable(project, "Finishing AI Workspace", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Merging ${workspace.branch} → $baseBranch (${strategy.label})"
                when (val r = service.finish(workspace, strategy, baseBranch, deleteBranch)) {
                    is WorkspaceService.FinishResult.Ok ->
                        Notifications.info(project, "Conductor", r.message)
                    is WorkspaceService.FinishResult.Dirty ->
                        Notifications.error(
                            project,
                            "Conductor — workspace is dirty",
                            "Commit or stash first. Changed files:\n${r.files.joinToString("\n")}",
                        )
                    is WorkspaceService.FinishResult.Conflict ->
                        Notifications.warn(project, "Conductor", r.message)
                    is WorkspaceService.FinishResult.Error ->
                        Notifications.error(project, "Conductor", r.message)
                }
            }
        }.queue()
    }
}
