package io.devdepot.conductor.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import io.devdepot.conductor.actions.openWorkspace
import io.devdepot.conductor.icons.ConductorIcons
import io.devdepot.conductor.toolwindow.actions.RefreshWorkspacesAction
import io.devdepot.conductor.toolwindow.actions.confirmAndDiscardWorkspaces
import io.devdepot.conductor.toolwindow.actions.promptAndRenameWorkspace
import io.devdepot.conductor.util.RelativeTime
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Trunk-mode panel: toolbar + scrollable list of workspaces. Open via
 * double-click; Open / Delete also available from the right-click menu.
 */
class TrunkPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(true, true), DataProvider {

    override fun getData(dataId: String): Any? =
        if (CommonDataKeys.PROJECT.`is`(dataId)) project else null

    private val listModel = DefaultListModel<Workspace>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
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
            add(OpenSkipStartupAction())
            add(RenameSelectedAction())
            add(DeleteSelectedAction())
        }
        list.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val index = list.locationToIndex(Point(x, y))
                if (index >= 0 && !list.isSelectedIndex(index)) {
                    list.selectedIndex = index
                }
                ActionManager.getInstance()
                    .createActionPopupMenu(ActionPlaces.TOOLWINDOW_POPUP, popup)
                    .component
                    .show(comp, x, y)
            }
        })

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val index = list.locationToIndex(event.point)
                if (index < 0) return false
                val workspace = listModel.getElementAt(index) ?: return false
                openWorkspace(project, workspace)
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

    private fun selectedWorkspaces(): List<Workspace> = list.selectedValuesList ?: emptyList()

    private inner class OpenSelectedAction :
        AnAction("Open", "Open this workspace in a new window", ConductorIcons.Open) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedWorkspaces().size == 1
        }

        override fun actionPerformed(e: AnActionEvent) {
            val only = selectedWorkspaces().singleOrNull() ?: return
            openWorkspace(project, only)
        }
    }

    private inner class OpenSkipStartupAction :
        AnAction(
            "Open Without Startup Command",
            "Open this workspace in a new window without running its startup command",
            ConductorIcons.Open,
        ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedWorkspaces().size == 1
        }

        override fun actionPerformed(e: AnActionEvent) {
            val only = selectedWorkspaces().singleOrNull() ?: return
            openWorkspace(project, only, skipStartupCommand = true)
        }
    }

    private inner class RenameSelectedAction :
        AnAction("Rename\u2026", "Rename this workspace", null) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedWorkspaces().size == 1
        }

        override fun actionPerformed(e: AnActionEvent) {
            val only = selectedWorkspaces().singleOrNull() ?: return
            promptAndRenameWorkspace(project, only)
        }
    }

    private inner class DeleteSelectedAction :
        AnAction("Delete", "Discard the selected workspace(s) and their branches", ConductorIcons.Delete) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val count = selectedWorkspaces().size
            e.presentation.isEnabled = count > 0
            e.presentation.text = if (count > 1) "Delete ($count)" else "Delete"
        }

        override fun actionPerformed(e: AnActionEvent) {
            val selected = selectedWorkspaces()
            if (selected.isEmpty()) return
            confirmAndDiscardWorkspaces(project, selected)
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
            append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (value.name != value.branch) {
                append("  ${value.branch}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            if (value.isCurrent) {
                append("  current", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            append(
                "    Created ${RelativeTime.format(value.createdAt)} · ${value.location}",
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
            ipad = JBUI.insets(4, 6)
        }
    }
}
