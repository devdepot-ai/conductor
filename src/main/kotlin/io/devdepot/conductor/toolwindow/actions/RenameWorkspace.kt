package io.devdepot.conductor.toolwindow.actions

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService

/**
 * Prompt for a new workspace name and rewrite the marker file. The marker is
 * the source of truth for `name`, so renaming is a marker-only operation —
 * the worktree directory and branch are unchanged.
 */
internal fun promptAndRenameWorkspace(project: Project, workspace: Workspace) {
    val input = Messages.showInputDialog(
        project,
        "New name for workspace `${workspace.name}`:",
        "Rename AI Workspace",
        null,
        workspace.name,
        object : InputValidator {
            override fun checkInput(input: String?): Boolean = !input.isNullOrBlank()
            override fun canClose(input: String?): Boolean = checkInput(input)
        },
    ) ?: return
    val newName = input.trim()
    if (newName == workspace.name) return

    val service = WorkspaceService.get(project)
    object : Task.Backgroundable(project, "Renaming AI Workspace", false) {
        override fun run(indicator: ProgressIndicator) {
            when (val r = service.rename(workspace, newName)) {
                is WorkspaceService.Result.Ok ->
                    Notifications.info(project, "Conductor", "Renamed to `$newName`.")
                is WorkspaceService.Result.Error ->
                    Notifications.error(project, "Conductor", r.message)
            }
        }
    }.queue()
}
