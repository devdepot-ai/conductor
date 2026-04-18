package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.devdepot.conductor.forge.Forge
import io.devdepot.conductor.forge.ForgeDetector
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.settings.MergeStrategy
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
            "Run finish command, open a PR, and optionally merge locally."
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

                if (Git.isDirty(workspace.location)) {
                    val files = Git.statusPorcelain(workspace.location).stdout.lines()
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

                val repo = Git.mainRepoRoot(workspace.location) ?: run {
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.error(project, "Conductor", "Could not locate trunk repository.")
                    }
                    return
                }
                val defaultBase = Git.detectDefaultBranch(repo)
                val branches = Git.listLocalBranches(repo).ifEmpty { listOf(defaultBase) }
                val forge = ForgeDetector.detect(repo)

                val snapshot = ConductorMarker.readConfig(workspace.location)
                val defaultStrategy = snapshot?.defaultMergeStrategy
                    ?.takeIf { it.isNotBlank() }
                    ?.let { MergeStrategy.fromId(it) }
                    ?: settings.defaultMergeStrategy

                val defaultPrTitle = Git.exec(
                    listOf("log", "-1", "--format=%s", workspace.branch),
                    workspace.location,
                ).stdout.ifBlank { workspace.branch }
                val bodyResult = Git.exec(
                    listOf("log", "$defaultBase..${workspace.branch}", "--format=- %s"),
                    workspace.location,
                )
                val defaultPrBody = if (bodyResult.ok) bodyResult.stdout else ""

                ApplicationManager.getApplication().invokeLater {
                    showDialogAndFinish(
                        project = project,
                        service = service,
                        workspace = workspace,
                        defaultBase = defaultBase,
                        branches = branches,
                        defaultStrategy = defaultStrategy,
                        forge = forge,
                        defaultPrTitle = defaultPrTitle,
                        defaultPrBody = defaultPrBody,
                    )
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
        forge: Forge,
        defaultPrTitle: String,
        defaultPrBody: String,
    ) {
        val settings = ConductorSettings.get(project)
        val hasFinishCommand = settings.finishCommand.isNotBlank()
        val dialog = FinishWorkspaceDialog(
            project = project,
            branch = workspace.branch,
            defaultBase = defaultBase,
            branches = branches,
            defaultStrategy = defaultStrategy,
            forge = forge,
            hasFinishCommand = hasFinishCommand,
            defaultRunFinishCommand = hasFinishCommand,
            defaultOpenPr = settings.createPrOnFinish && forge != Forge.NONE,
            defaultMergeLocally = settings.localFinishEnabled,
            defaultPrTitle = defaultPrTitle,
            defaultPrBody = defaultPrBody,
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

        val request = WorkspaceService.FinishRequest(
            workspace = workspace,
            runFinishCommand = dialog.runFinishCommand,
            openPr = dialog.openPr,
            prTitle = dialog.prTitle,
            prBody = dialog.prBody,
            mergeLocally = dialog.mergeLocally,
            baseBranch = dialog.baseBranch,
            deleteBranch = dialog.deleteBranch,
            strategy = dialog.strategy,
        )
        queueFinish(project, service, request)
    }

    private fun queueFinish(
        project: Project,
        service: WorkspaceService,
        request: WorkspaceService.FinishRequest,
    ) {
        object : Task.Backgroundable(project, "Finishing AI Workspace", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Finishing ${request.workspace.branch}"
                when (val r = service.finish(request)) {
                    is WorkspaceService.FinishResult.Ok ->
                        Notifications.info(project, "Conductor", r.message)
                    is WorkspaceService.FinishResult.PrOpened -> {
                        val suffix = if (r.awaitingMerge) " Workspace will be reaped on merge." else ""
                        Notifications.info(
                            project,
                            "Conductor",
                            "Opened PR #${r.number}: ${r.url}.$suffix",
                        )
                    }
                    is WorkspaceService.FinishResult.PrCreateFailed ->
                        Notifications.error(project, "Conductor", r.message)
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
