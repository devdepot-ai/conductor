package io.devdepot.conductor.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import javax.swing.SwingUtilities
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

object WorkspaceTerminalLauncher {
    private val log = Logger.getInstance(WorkspaceTerminalLauncher::class.java)

    fun launch(project: Project, tabName: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val manager = TerminalToolWindowManager.getInstance(project)
                val widget = manager.createShellWidget(project.basePath, tabName, true, true)
                pinContent(project, widget)
            } catch (e: Throwable) {
                log.warn("Failed to launch terminal for $tabName", e)
            }
        }
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
