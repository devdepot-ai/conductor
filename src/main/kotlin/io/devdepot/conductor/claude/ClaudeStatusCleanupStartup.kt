package io.devdepot.conductor.claude

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import io.devdepot.conductor.workspace.ConductorMarker
import io.devdepot.conductor.workspace.WorkspaceTopics
import java.nio.file.Files
import java.nio.file.Path

/**
 * When a Conductor workspace IDE window closes, drop its state files under
 * `.conductor/claude/` so the trunk panel stops showing live status for
 * sessions that are no longer running.
 *
 * Relies on Disposer-on-project lifecycle, which fires when the project is
 * closed regardless of whether the OS terminated the underlying terminal
 * cleanly (so we catch the case where Claude got SIGHUP and never wrote
 * SessionEnd).
 */
class ClaudeStatusCleanupStartup : ProjectActivity {

    private val log = Logger.getInstance(ClaudeStatusCleanupStartup::class.java)

    override suspend fun execute(project: Project) {
        val base = project.basePath ?: return
        val path = Path.of(base)
        if (!ConductorMarker.isWorkspace(path)) return

        Disposer.register(project, Disposable {
            cleanupStateDir(path)
            broadcastToOtherProjects(project)
        })
    }

    private fun cleanupStateDir(workspaceRoot: Path) {
        val dir = workspaceRoot.resolve(".conductor").resolve("claude")
        if (!Files.isDirectory(dir)) return
        try {
            Files.list(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        } catch (e: Throwable) {
            log.debug("Cleanup of $dir failed: ${e.message}")
        }
    }

    /**
     * VFS_CHANGES will fire for the deletes, but only after some delay; nudge
     * other open Conductor projects (typically the trunk window) to refresh
     * immediately so the closed workspace's row clears without lag.
     */
    private fun broadcastToOtherProjects(closing: Project) {
        ApplicationManager.getApplication().invokeLater {
            for (p in ProjectManager.getInstance().openProjects) {
                if (p === closing || p.isDisposed) continue
                p.messageBus.syncPublisher(WorkspaceTopics.CHANGED).changed()
            }
        }
    }
}
