package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.settings.MergeStrategy
import io.devdepot.conductor.ui.FinishWorkspaceDialog
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.ConductorMarker
import io.devdepot.conductor.workspace.WorkspaceService

class FinishWorkspaceAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null &&
            ActionContext.isWorkspace(project) &&
            WorkspaceService.get(project).current() != null
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
        val workspace = service.current() ?: run {
            Notifications.warn(project, "Conductor", "Finish AI Workspace must be run from inside a Conductor worktree.")
            return
        }

        // J3 step 2: hard-error on dirty.
        if (Git.isDirty(workspace.path)) {
            val files = Git.statusPorcelain(workspace.path).stdout.lines()
                .filter { it.isNotBlank() }
                .joinToString("\n")
            Notifications.error(
                project,
                "Conductor — worktree is dirty",
                "Commit or stash first. Changed files:\n$files",
            )
            return
        }

        val repo = Git.mainRepoRoot(workspace.path) ?: run {
            Notifications.error(project, "Conductor", "Could not locate main repository.")
            return
        }
        val defaultBase = Git.detectDefaultBranch(repo)
        val branches = Git.listLocalBranches(repo).ifEmpty { listOf(defaultBase) }

        val snapshot = ConductorMarker.readConfig(workspace.path)
        val defaultStrategy = snapshot?.defaultMergeStrategy
            ?.let { MergeStrategy.fromId(it) }
            ?: settings.defaultMergeStrategy

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
                "Discard `${workspace.branch}`? This deletes the branch and worktree. Unmerged commits will be lost.",
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

        object : Task.Backgroundable(project, "Finishing AI Workspace", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Merging ${workspace.branch} → $baseBranch (${strategy.label})"
                when (val r = service.finish(workspace, strategy, baseBranch, deleteBranch)) {
                    is WorkspaceService.FinishResult.Ok ->
                        Notifications.info(project, "Conductor", r.message)
                    is WorkspaceService.FinishResult.Dirty ->
                        Notifications.error(
                            project,
                            "Conductor — worktree is dirty",
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
