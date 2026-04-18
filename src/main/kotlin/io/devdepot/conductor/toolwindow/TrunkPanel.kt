package io.devdepot.conductor.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import io.devdepot.conductor.actions.openWorkspace
import io.devdepot.conductor.icons.ConductorIcons
import io.devdepot.conductor.toolwindow.actions.DeleteWorkspaceAction
import io.devdepot.conductor.toolwindow.actions.RefreshWorkspacesAction
import io.devdepot.conductor.util.RelativeTime
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredListCellRenderer
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Trunk-mode panel: toolbar + scrollable list of workspaces.
 *
 * Toolbar reuses the existing `Conductor.NewWorkspace` action so the panel
 * stays in lockstep with the menu/split-button. Double-click or the popup
 * menu drive open/delete through the existing helpers.
 */
class TrunkPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(true, true) {

    private val listModel = DefaultListModel<Workspace>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = WorkspaceCellRenderer()
        emptyText.text = "No workspaces"
        emptyText.appendSecondaryText(
            "Click + to create one",
            StatusText.DEFAULT_ATTRIBUTES,
            null,
        )
    }

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT,
            buildToolbarGroup(),
            true,
        ).apply {
            targetComponent = this@TrunkPanel
        }
        setToolbar(toolbar.component)

        val popup = DefaultActionGroup().apply {
            add(OpenSelectedAction())
            add(DeleteWorkspaceAction { list.selectedValue })
        }
        PopupHandler.installPopupMenu(list, popup, ActionPlaces.TOOLWINDOW_POPUP)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val selected = list.selectedValue ?: return false
                openWorkspace(project, selected)
                return true
            }
        }.installOn(list)

        setContent(ScrollPaneFactory.createScrollPane(list, true))
    }

    private fun buildToolbarGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        ActionManager.getInstance().getAction("Conductor.NewWorkspace")?.let { group.add(it) }
        group.add(RefreshWorkspacesAction())
        return group
    }

    fun reload() {
        object : Task.Backgroundable(project, "Loading AI Workspaces…", false) {
            override fun run(indicator: ProgressIndicator) {
                val workspaces = WorkspaceService.get(project).list()
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    listModel.clear()
                    workspaces.forEach(listModel::addElement)
                }, ModalityState.any())
            }
        }.queue()
    }

    private inner class OpenSelectedAction :
        com.intellij.openapi.actionSystem.AnAction("Open", "Open this workspace in a new window", ConductorIcons.InWorkspace) {
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
            e.presentation.isEnabled = list.selectedValue != null
        }

        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
            val selected = list.selectedValue ?: return
            openWorkspace(project, selected)
        }
    }

    private class WorkspaceCellRenderer : ColoredListCellRenderer<Workspace>() {
        override fun customizeCellRenderer(
            list: JList<out Workspace>,
            value: Workspace,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            icon = ConductorIcons.InWorkspace
            append(value.branch, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (value.isCurrent) {
                append("  current", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            val createdLabel = "Created ${RelativeTime.format(value.createdAt)}"
            val pathLabel = value.location.toString()
            append(
                "    $createdLabel · $pathLabel",
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
            ipad = JBUI.insets(4, 6)
        }
    }
}
