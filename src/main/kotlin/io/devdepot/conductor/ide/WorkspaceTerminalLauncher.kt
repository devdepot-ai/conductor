package io.devdepot.conductor.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import io.devdepot.conductor.claude.ClaudeCodeDetector
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.settings.TerminalPosition
import io.devdepot.conductor.workspace.ConductorMarker
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

object WorkspaceTerminalLauncher {
    private val log = Logger.getInstance(WorkspaceTerminalLauncher::class.java)

    fun launch(project: Project, tabName: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                applyAnchor(project)
                val manager = TerminalToolWindowManager.getInstance(project)
                val widget = manager.createShellWidget(project.basePath, tabName, true, true)
                pinContent(project, widget)
                resolveTerminalStartCommand(project)?.let { cmd ->
                    runCatching { widget.sendCommandToExecute(cmd) }
                        .onFailure { log.warn("Failed to send terminal start command", it) }
                }
            } catch (e: Throwable) {
                log.warn("Failed to launch terminal for $tabName", e)
            }
        }
    }

    /**
     * Marker > settings > "claude" when Claude hooks are installed. Blank
     * result means "send nothing — leave the user at a plain prompt".
     *
     * When the marker carries an initial prompt (captured at creation while
     * Claude integration was on), it is appended to the resolved command as a
     * shell-quoted CLI argument — e.g. `claude 'review the diff'` — so Claude
     * starts with that first message already submitted. This is more robust
     * than typing into the running session, which would race Claude's startup.
     */
    private fun resolveTerminalStartCommand(project: Project): String? {
        val base = project.basePath?.let(Path::of)
        val config = base?.let { ConductorMarker.readConfig(it) }
        val fromMarker = config?.terminalStartCommand
        val raw = fromMarker?.takeIf { it.isNotBlank() }
            ?: ConductorSettings.get(project).terminalStartCommand.takeIf { it.isNotBlank() }
        val claudeDefault = if (ClaudeCodeDetector.get().get() == ClaudeCodeDetector.State.InstalledWithHooks) {
            "claude"
        } else {
            null
        }
        val command = (raw ?: claudeDefault) ?: return null
        val prompt = config?.initialPrompt?.takeIf { it.isNotBlank() } ?: return command
        return "$command ${singleQuote(prompt)}"
    }

    /** POSIX single-quote a string so it survives the shell as one argument. */
    private fun singleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun applyAnchor(project: Project) {
        val desired = resolveTerminalPosition(project)
        val anchor = when (desired) {
            TerminalPosition.BOTTOM -> ToolWindowAnchor.BOTTOM
            TerminalPosition.RIGHT -> ToolWindowAnchor.RIGHT
            TerminalPosition.LEFT -> ToolWindowAnchor.LEFT
        }
        val terminal = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        if (terminal.anchor != anchor) terminal.setAnchor(anchor, null)
    }

    /**
     * Workspace projects don't have their own .conductor/settings.json — that
     * lives in the trunk. The marker file snapshots the relevant settings at
     * creation, so prefer it; fall back to ConductorSettings only for trunk
     * projects or pre-marker-snapshot workspaces.
     */
    private fun resolveTerminalPosition(project: Project): TerminalPosition {
        val base = project.basePath?.let(Path::of)
        if (base != null) {
            ConductorMarker.readConfig(base)?.terminalPosition?.let {
                return TerminalPosition.fromId(it)
            }
        }
        return ConductorSettings.get(project).terminalPosition
    }

    private fun pinContent(project: Project, widget: TerminalWidget) {
        val contentManager = ToolWindowManager.getInstance(project)
            .getToolWindow("Terminal")
            ?.contentManager ?: return
        val widgetComponent = widget.component
        val content = contentManager.contents.firstOrNull { c ->
            SwingUtilities.isDescendingFrom(widgetComponent, c.component)
        } ?: contentManager.selectedContent
        content?.isPinned = true
    }
}
