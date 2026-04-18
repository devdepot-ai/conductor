package io.devdepot.conductor.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.devdepot.conductor.actions.ActionContext
import io.devdepot.conductor.workspace.WorkspaceChangeNotifier
import io.devdepot.conductor.workspace.WorkspaceTopics
import javax.swing.JComponent

/**
 * Single tool window whose content swaps between three panels based on the
 * project's [ActionContext] mode. The mode is re-evaluated whenever
 * [WorkspaceService] fires [WorkspaceTopics.CHANGED] so that creating or
 * finishing a workspace flips the panel without manual refresh.
 */
class ConductorToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val holder = PanelHolder(project, toolWindow)
        holder.install(resolveMode(project))

        val connection = project.messageBus.connect(toolWindow.disposable)
        connection.subscribe(
            WorkspaceTopics.CHANGED,
            WorkspaceChangeNotifier {
                val mode = resolveMode(project)
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    holder.syncWithMode(mode)
                }, project.disposed)
            },
        )
    }

    private fun resolveMode(project: Project): Mode = when {
        ActionContext.isWorkspace(project) -> Mode.WORKSPACE
        ActionContext.isTrunk(project) -> Mode.TRUNK
        else -> Mode.NEITHER
    }

    private enum class Mode { TRUNK, WORKSPACE, NEITHER }

    private class PanelHolder(
        private val project: Project,
        private val toolWindow: ToolWindow,
    ) {
        private var mode: Mode? = null
        private var panel: JComponent? = null
        private var trunkPanel: TrunkPanel? = null

        fun install(mode: Mode) {
            val component = buildPanel(mode)
            val content = ContentFactory.getInstance().createContent(component, null, false)
            content.isCloseable = false
            toolWindow.contentManager.removeAllContents(true)
            toolWindow.contentManager.addContent(content)
            this.mode = mode
            this.panel = component
        }

        fun syncWithMode(nextMode: Mode) {
            if (nextMode != mode) {
                install(nextMode)
                return
            }
            trunkPanel?.reload()
            (panel as? WorkspacePanel)?.reload()
        }

        private fun buildPanel(mode: Mode): JComponent {
            trunkPanel = null
            return when (mode) {
                Mode.TRUNK -> TrunkPanel(project, toolWindow).also {
                    trunkPanel = it
                    it.reload()
                }
                Mode.WORKSPACE -> WorkspacePanel(project, toolWindow).also { it.reload() }
                Mode.NEITHER -> EmptyStatePanel()
            }
        }
    }
}
