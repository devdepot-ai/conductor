package io.devdepot.conductor.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

object ClaudeTerminalLauncher {
    private val log = Logger.getInstance(ClaudeTerminalLauncher::class.java)

    fun launchClaudeOnly(project: Project, tabName: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val manager = TerminalToolWindowManager.getInstance(project)
                val basePath = project.basePath
                val widget = manager.createShellWidget(basePath, "$tabName · claude", true, true)
                widget.sendCommandToExecute("claude")
            } catch (e: Throwable) {
                log.warn("Failed to launch claude terminal for $tabName", e)
            }
        }
    }
}
