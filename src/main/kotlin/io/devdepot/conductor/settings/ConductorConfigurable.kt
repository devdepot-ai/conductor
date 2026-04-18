package io.devdepot.conductor.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import io.devdepot.conductor.workspace.ConductorMarker
import java.nio.file.Path
import javax.swing.JComboBox
import javax.swing.JComponent

class ConductorConfigurable(private val project: Project) : Configurable {

    private val settings = ConductorSettings.get(project)
    private val startupCommandField = JBTextField()
    private val openTerminalCheckbox = JBCheckBox("Open terminal on workspace open")
    private val worktreeRootField = TextFieldWithBrowseButton()
    private val strategyBox = JComboBox(MergeStrategy.values())

    private var rootPanel: JComponent? = null
    private val workspaceMode: Boolean by lazy {
        val base = project.basePath ?: return@lazy false
        ConductorMarker.isWorkspace(Path.of(base))
    }

    override fun getDisplayName(): String = "Conductor"

    override fun createComponent(): JComponent {
        return if (workspaceMode) buildWorkspacePanel() else buildTrunkPanel()
    }

    private fun buildTrunkPanel(): JComponent {
        worktreeRootField.addBrowseFolderListener(
            "Worktree Root",
            "Parent directory for new worktrees. Leave blank for a [repo-name]-worktrees/ sibling folder.",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )

        reset()

        val panel = panel {
            row("Startup command:") {
                cell(startupCommandField)
                    .comment(
                        "Runs in the background as an IDE task when the workspace opens. " +
                            "Shown as a status-bar progress item; click to view output.",
                    )
            }
            row("") {
                cell(openTerminalCheckbox)
                    .comment("When enabled, a terminal tab runs <code>claude</code> on workspace open.")
            }
            row("Worktree root:") {
                cell(worktreeRootField)
                    .comment("Blank = a sibling folder named [repo-name]-worktrees/ next to the main repo.")
            }
            row("Default merge strategy:") {
                cell(strategyBox)
            }
        }
        rootPanel = panel
        return panel
    }

    private fun buildWorkspacePanel(): JComponent {
        val basePath = project.basePath?.let { Path.of(it) }
        val snapshot = basePath?.let { ConductorMarker.readConfig(it) }
        startupCommandField.text = snapshot?.startupCommand ?: settings.startupCommand
        openTerminalCheckbox.isSelected = snapshot?.openTerminalOnStart ?: settings.openTerminalOnStart
        worktreeRootField.text = settings.worktreeRoot
        strategyBox.selectedItem = snapshot?.defaultMergeStrategy
            ?.let { MergeStrategy.fromId(it) }
            ?: settings.defaultMergeStrategy

        val panel = panel {
            row {
                text(
                    "This is an AI Workspace. Settings are managed from the main repository. " +
                        "Values shown are the snapshot this workspace was created with.",
                )
            }
            row("Startup command:") {
                cell(startupCommandField).enabled(false)
            }
            row("") {
                cell(openTerminalCheckbox).enabled(false)
            }
            row("Default merge strategy:") {
                cell(strategyBox).enabled(false)
            }
        }
        rootPanel = panel
        return panel
    }

    override fun isModified(): Boolean {
        if (workspaceMode) return false
        return startupCommandField.text != settings.startupCommand ||
            openTerminalCheckbox.isSelected != settings.openTerminalOnStart ||
            worktreeRootField.text != settings.worktreeRoot ||
            (strategyBox.selectedItem as? MergeStrategy) != settings.defaultMergeStrategy
    }

    override fun apply() {
        if (workspaceMode) return
        settings.startupCommand = startupCommandField.text.trim()
        settings.openTerminalOnStart = openTerminalCheckbox.isSelected
        settings.worktreeRoot = worktreeRootField.text.trim()
        settings.defaultMergeStrategy = strategyBox.selectedItem as? MergeStrategy ?: MergeStrategy.MERGE_NO_FF
    }

    override fun reset() {
        if (workspaceMode) return
        startupCommandField.text = settings.startupCommand
        openTerminalCheckbox.isSelected = settings.openTerminalOnStart
        worktreeRootField.text = settings.worktreeRoot
        strategyBox.selectedItem = settings.defaultMergeStrategy
    }

    override fun disposeUIResources() {
        rootPanel = null
    }
}
