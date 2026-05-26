package io.devdepot.conductor.claude

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.devdepot.conductor.workspace.WorkspaceChangeNotifier
import io.devdepot.conductor.workspace.WorkspaceTopics

/**
 * Watches the VFS for writes inside any `<workspace>/.conductor/claude/`
 * directory and triggers a WorkspaceChangeNotifier broadcast on every open
 * project. The tool window panels already subscribe to that topic; this is
 * the cheapest way to plumb status updates through.
 */
class ClaudeStateFileListener : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (!events.any { it.touchesClaudeState() }) return
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                project.messageBus.syncPublisher(WorkspaceTopics.CHANGED).changed()
            }
        }
    }

    private fun VFileEvent.touchesClaudeState(): Boolean {
        val path = when (this) {
            is VFileCreateEvent -> parent.path + "/" + childName
            is VFileDeleteEvent -> file.path
            else -> file?.path
        } ?: return false
        // Path contains "/.conductor/claude/" and ends with .json
        return path.contains("/.conductor/claude/") && path.endsWith(".json")
    }
}
