package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import git4idea.GitBranch
import git4idea.actions.branch.GitSingleBranchAction
import git4idea.repo.GitRepository

/**
 * "Open as AI Workspace" in the native Git branches popup. Opens the New AI
 * Workspace dialog with the selected branch preselected in "check out existing
 * branch" mode, so the user can confirm the startup command / Claude prompt
 * before the worktree + IDE window are created.
 *
 * Only offered from the trunk window (matching [NewWorkspaceAction]); inside a
 * workspace it stays hidden.
 */
class OpenBranchAsWorkspaceAction : GitSingleBranchAction() {

    override fun updateIfEnabledAndVisible(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        branch: GitBranch,
    ) {
        e.presentation.isEnabledAndVisible = ActionContext.isTrunk(project)
    }

    override fun actionPerformed(
        e: AnActionEvent,
        project: Project,
        repositories: List<GitRepository>,
        branch: GitBranch,
    ) {
        NewWorkspaceLauncher.launch(project, preselectExistingBranch = branch.name)
    }
}
