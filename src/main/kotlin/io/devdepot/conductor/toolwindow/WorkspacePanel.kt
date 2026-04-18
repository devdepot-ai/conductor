package io.devdepot.conductor.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.devdepot.conductor.toolwindow.actions.RefreshWorkspacesAction
import io.devdepot.conductor.toolwindow.actions.promptAndRenameWorkspace
import io.devdepot.conductor.util.RelativeTime
import io.devdepot.conductor.workspace.ConductorMarker
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService
import javax.swing.JPanel

/**
 * Workspace-mode panel: toolbar with Finish + Refresh; centre shows minimal
 * details of the current workspace. Intentionally sparse — more surfaces
 * will land here later.
 */
class WorkspacePanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(true, true), DataProvider {

    override fun getData(dataId: String): Any? =
        if (CommonDataKeys.PROJECT.`is`(dataId)) project else null

    private val branchLabel = JBLabel()
    private val nameLabel = JBLabel()
    private val pathLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val createdLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val prLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val emptyLabel = JBLabel("No current workspace.").apply {
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    }

    private var currentWorkspace: Workspace? = null

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT,
            buildToolbarGroup(),
            true,
        ).apply {
            targetComponent = this@WorkspacePanel
        }
        setToolbar(toolbar.component)
        val content = buildContent()
        val popup = DefaultActionGroup().apply { add(RenameCurrentAction()) }
        PopupHandler.installPopupMenu(content, popup, ActionPlaces.TOOLWINDOW_POPUP)
        setContent(content)
    }

    private fun buildToolbarGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        ActionManager.getInstance().getAction("Conductor.FinishWorkspace")?.let { group.add(it) }
        ActionManager.getInstance().getAction("Conductor.OpenPr")?.let { group.add(it) }
        group.add(RefreshWorkspacesAction())
        return group
    }

    private fun buildContent(): JPanel {
        val details = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Branch:"), branchLabel)
            .addLabeledComponent(JBLabel("Name:"), nameLabel)
            .addLabeledComponent(JBLabel("Path:"), pathLabel)
            .addLabeledComponent(JBLabel("Created:"), createdLabel)
            .addLabeledComponent(JBLabel("PR:"), prLabel)
            .addComponent(emptyLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        details.border = JBUI.Borders.empty(10)
        return details
    }

    fun reload() {
        populate(WorkspaceService.get(project).current())
    }

    private fun populate(current: Workspace?) {
        currentWorkspace = current
        if (current == null) {
            branchLabel.text = ""
            nameLabel.text = ""
            pathLabel.text = ""
            createdLabel.text = ""
            prLabel.text = ""
            emptyLabel.isVisible = true
            return
        }
        branchLabel.text = current.branch
        nameLabel.text = current.name
        pathLabel.text = current.location.toString()
        createdLabel.text = RelativeTime.format(current.createdAt)
        prLabel.text = formatPr(current)
        emptyLabel.isVisible = false
    }

    private fun formatPr(workspace: Workspace): String {
        val pr = ConductorMarker.readConfig(workspace.location)?.pr ?: return "—"
        return "#${pr.number} · ${pr.state}"
    }

    private inner class RenameCurrentAction :
        AnAction("Rename\u2026", "Rename this workspace", null) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentWorkspace != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val ws = currentWorkspace ?: return
            promptAndRenameWorkspace(project, ws)
        }
    }
}
