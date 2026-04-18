package io.devdepot.conductor.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.devdepot.conductor.toolwindow.actions.RefreshWorkspacesAction
import io.devdepot.conductor.util.RelativeTime
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
) : SimpleToolWindowPanel(true, true) {

    private val branchLabel = JBLabel()
    private val nameLabel = JBLabel()
    private val pathLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val createdLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val emptyLabel = JBLabel("No current workspace.").apply {
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    }

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT,
            buildToolbarGroup(),
            true,
        ).apply {
            targetComponent = this@WorkspacePanel
        }
        setToolbar(toolbar.component)
        setContent(buildContent())
    }

    private fun buildToolbarGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        ActionManager.getInstance().getAction("Conductor.FinishWorkspace")?.let { group.add(it) }
        group.add(RefreshWorkspacesAction())
        return group
    }

    private fun buildContent(): JPanel {
        val details = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Branch:"), branchLabel)
            .addLabeledComponent(JBLabel("Name:"), nameLabel)
            .addLabeledComponent(JBLabel("Path:"), pathLabel)
            .addLabeledComponent(JBLabel("Created:"), createdLabel)
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
        if (current == null) {
            branchLabel.text = ""
            nameLabel.text = ""
            pathLabel.text = ""
            createdLabel.text = ""
            emptyLabel.isVisible = true
            return
        }
        branchLabel.text = current.branch
        nameLabel.text = current.name
        pathLabel.text = current.location.toString()
        createdLabel.text = RelativeTime.format(current.createdAt)
        emptyLabel.isVisible = false
    }
}
