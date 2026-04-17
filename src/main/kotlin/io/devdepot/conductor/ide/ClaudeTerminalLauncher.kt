package io.devdepot.conductor.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

object ClaudeTerminalLauncher {
    private val log = Logger.getInstance(ClaudeTerminalLauncher::class.java)

    /**
     * Opens a terminal tab named "<tabName> · claude" and runs a single chained command:
     *   - `<startupScript> && claude` when the script path is non-blank
     *   - `claude` otherwise
     * The script path is shell-quoted when interpolated.
     */
    fun launch(project: Project, tabName: String, startupScriptPath: String?) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val manager = TerminalToolWindowManager.getInstance(project)
                val basePath = project.basePath
                val widget = manager.createShellWidget(basePath, "$tabName · claude", true, true)
                val command = buildCommand(startupScriptPath)
                widget.sendCommandToExecute(command)
            } catch (e: Throwable) {
                log.warn("Failed to launch claude terminal for $tabName", e)
            }
        }
    }

    internal fun buildCommand(startupScriptPath: String?): String {
        val script = startupScriptPath?.trim().orEmpty()
        return if (script.isEmpty()) "claude" else "${shellQuote(script)} && claude"
    }

    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        // Single-quote and escape any embedded single quotes.
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
