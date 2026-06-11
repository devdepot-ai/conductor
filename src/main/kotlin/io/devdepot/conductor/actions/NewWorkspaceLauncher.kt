package io.devdepot.conductor.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.devdepot.conductor.claude.ClaudeCodeDetector
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.ui.worktree.NewWorktreeWorkspaceDialog
import io.devdepot.conductor.workspace.WorkspaceService

/**
 * Shared entry point for the "New AI Workspace" flow: prepares the git context
 * off the EDT, shows [NewWorktreeWorkspaceDialog], and creates the workspace on
 * accept. Used both by [NewWorkspaceAction] (blank dialog) and by
 * [OpenBranchAsWorkspaceAction] (dialog with an existing branch preselected).
 */
object NewWorkspaceLauncher {

    /**
     * @param preselectExistingBranch when non-blank, opens the dialog in
     *   "check out existing branch" mode with this branch chosen.
     */
    fun launch(project: Project, preselectExistingBranch: String? = null) {
        val service = WorkspaceService.get(project)
        val settings = ConductorSettings.get(project)

        object : Task.Backgroundable(project, "Preparing AI Workspace…", false) {
            override fun run(indicator: ProgressIndicator) {
                val trunk = service.trunk() ?: run {
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.error(project, "Conductor", "Could not locate the trunk repository.")
                    }
                    return
                }
                val defaultBase = Git.detectDefaultBranch(trunk)
                val branches = Git.listAllBranches(trunk)
                // Existing branches available to check out: local branches not
                // already occupied by a worktree, plus remote-tracking refs.
                val occupied = Git.listWorktrees(trunk).mapNotNull { it.branch }.toSet()
                val local = Git.listLocalBranches(trunk)
                val remote = branches - local.toSet()
                val existingBranches = local.filter { it !in occupied } + remote
                val defaultName = Git.generateBranchName(settings.branchPrefix)
                val hasStartupCommand = settings.startupCommand.isNotBlank()
                val claudeEnabled =
                    ClaudeCodeDetector.get().get() == ClaudeCodeDetector.State.InstalledWithHooks

                ApplicationManager.getApplication().invokeLater {
                    showDialogAndCreate(
                        project,
                        service,
                        defaultName,
                        defaultBase,
                        branches,
                        existingBranches,
                        hasStartupCommand,
                        claudeEnabled,
                        settings.startupCommand,
                        preselectExistingBranch,
                    )
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
        existingBranches: List<String>,
        hasStartupCommand: Boolean,
        claudeEnabled: Boolean,
        defaultStartupCommand: String,
        preselectExistingBranch: String?,
    ) {
        val dialog = NewWorktreeWorkspaceDialog(
            project,
            defaultName,
            defaultBase,
            branches,
            existingBranches,
            hasStartupCommand,
            claudeEnabled,
            defaultStartupCommand,
            preselectExistingBranch,
        )
        if (!dialog.showAndGet()) return

        val useExisting = dialog.useExistingBranch
        val branchName = if (useExisting) dialog.existingBranch else dialog.name.trim()
        val baseBranch = if (useExisting) "" else dialog.baseBranch
        val initialPrompt = dialog.initialPrompt.takeIf { it.isNotBlank() }
        // Worktree dir: last path segment so `origin/feature` → `feature`.
        val slug = if (useExisting) {
            branchName.substringAfterLast('/')
        } else {
            branchName.substringAfter('/', branchName)
        }
        val skip = hasStartupCommand && !dialog.runStartupCommand
        val startupOverride = if (skip) null else dialog.startupCommand

        object : Task.Backgroundable(project, "Creating AI Workspace", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = if (useExisting) {
                    "git worktree add $branchName"
                } else {
                    "git worktree add -b $branchName"
                }
                when (
                    val r = service.create(
                        branchName,
                        baseBranch,
                        slug,
                        skipStartupCommand = skip,
                        useExistingBranch = useExisting,
                        initialPrompt = initialPrompt,
                        startupCommand = startupOverride,
                    )
                ) {
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
