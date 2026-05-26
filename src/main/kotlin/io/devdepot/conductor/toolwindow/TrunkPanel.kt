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
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.StatusText
import io.devdepot.conductor.actions.openWorkspace
import io.devdepot.conductor.icons.ConductorIcons
import io.devdepot.conductor.toolwindow.actions.RefreshWorkspacesAction
import io.devdepot.conductor.toolwindow.actions.confirmAndDiscardWorkspaces
import io.devdepot.conductor.toolwindow.actions.promptAndRenameWorkspace
import io.devdepot.conductor.util.RelativeTime
import io.devdepot.conductor.workspace.ConductorMarker
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.TableCellRenderer

/**
 * Trunk-mode panel: toolbar + scrollable table of workspaces. Open via
 * double-click; Open / Delete also available from the right-click menu.
 */
class TrunkPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(true, true), DataProvider {

    override fun getData(dataId: String): Any? =
        if (CommonDataKeys.PROJECT.`is`(dataId)) project else null

    private val tableModel = ListTableModel<Workspace>(
        NameColumn(),
        BranchColumn(),
        PrColumn(),
        CreatedColumn(),
    )

    private val table = TableView(tableModel).apply {
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        rowHeight = JBUI.scale(22)
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        tableHeader.reorderingAllowed = false
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
        table.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val row = table.rowAtPoint(Point(x, y))
                if (row >= 0 && !table.isRowSelected(row)) {
                    table.setRowSelectionInterval(row, row)
                }
                ActionManager.getInstance()
                    .createActionPopupMenu(ActionPlaces.TOOLWINDOW_POPUP, popup)
                    .component
                    .show(comp, x, y)
            }
        })

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = table.rowAtPoint(event.point)
                if (row < 0) return false
                val workspace = workspaceAt(row) ?: return false
                openWorkspace(project, workspace)
                return true
            }
        }.installOn(table)

        adjustColumnWidths()

        setContent(ScrollPaneFactory.createScrollPane(table, true))
    }

    private fun adjustColumnWidths() {
        val cols = table.columnModel
        cols.getColumn(0).preferredWidth = JBUI.scale(180) // Name
        cols.getColumn(1).preferredWidth = JBUI.scale(160) // Branch
        cols.getColumn(2).preferredWidth = JBUI.scale(110) // PR
        cols.getColumn(3).preferredWidth = JBUI.scale(110) // Created
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
                    tableModel.items = workspaces
                }, ModalityState.any())
            }
        }.queue()
    }

    private fun workspaceAt(viewRow: Int): Workspace? {
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        if (modelRow < 0 || modelRow >= tableModel.rowCount) return null
        return tableModel.getItem(modelRow)
    }

    private fun selectedWorkspaces(): List<Workspace> =
        table.selectedRows.toList().mapNotNull { workspaceAt(it) }

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
        AnAction("Rename…", "Rename this workspace", null) {
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

    private abstract class WorkspaceColumn(title: String) : ColumnInfo<Workspace, Workspace>(title) {
        override fun valueOf(item: Workspace?): Workspace? = item
        override fun getRenderer(item: Workspace?): TableCellRenderer = cellRenderer
        protected abstract val cellRenderer: TableCellRenderer
    }

    private class NameColumn : WorkspaceColumn("Name") {
        override val cellRenderer: TableCellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int,
            ) {
                val ws = value as? Workspace ?: return
                icon = ConductorIcons.InWorkspace
                val attrs = if (ws.isOpen) {
                    SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                } else {
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                }
                append(ws.name, attrs)
                if (ws.isCurrent) {
                    append("  current", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                } else if (ws.isOpen) {
                    append("  ● open", OPEN_ATTRIBUTES)
                }
                toolTipText = ws.location.toString()
                ipad = JBUI.insets(4, 6)
            }
        }
    }

    private class BranchColumn : WorkspaceColumn("Branch") {
        override val cellRenderer: TableCellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int,
            ) {
                val ws = value as? Workspace ?: return
                append(ws.branch, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                toolTipText = ws.branch
                ipad = JBUI.insets(4, 6)
            }
        }
    }

    private class PrColumn : WorkspaceColumn("PR") {
        override val cellRenderer: TableCellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int,
            ) {
                val ws = value as? Workspace ?: return
                val pr = ConductorMarker.readConfig(ws.location)?.pr
                if (pr == null) {
                    append("—", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                } else {
                    append("#${pr.number}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(" · ${pr.state}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                ipad = JBUI.insets(4, 6)
            }
        }
    }

    private class CreatedColumn : WorkspaceColumn("Created") {
        override val cellRenderer: TableCellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int,
            ) {
                val ws = value as? Workspace ?: return
                append(RelativeTime.format(ws.createdAt), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                ipad = JBUI.insets(4, 6)
            }
        }
    }

    companion object {
        private val OPEN_ATTRIBUTES = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            com.intellij.ui.JBColor(0x2E7D32, 0x7BC67B),
        )
    }
}
