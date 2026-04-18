package io.devdepot.conductor.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

object WorkspaceTerminalLauncher {
    private val log = Logger.getInstance(WorkspaceTerminalLauncher::class.java)

    fun launch(project: Project, tabName: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val manager = TerminalToolWindowManager.getInstance(project)
                val basePath = project.basePath
                manager.createShellWidget(basePath, tabName, true, true)
            } catch (e: Throwable) {
                log.warn("Failed to launch terminal for $tabName", e)
            }
        }
    }
}
