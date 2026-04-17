package io.devdepot.conductor.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComboBox
import javax.swing.JComponent

class ConductorConfigurable(private val project: Project) : Configurable {

    private val settings = ConductorSettings.get(project)
    private val scriptField = TextFieldWithBrowseButton()
    private val worktreeRootField = TextFieldWithBrowseButton()
    private val strategyBox = JComboBox(MergeStrategy.values())

    private var rootPanel: JComponent? = null

    override fun getDisplayName(): String = "Conductor"

    override fun createComponent(): JComponent {
        scriptField.addBrowseFolderListener(
            "Startup Script",
            "Script run before `claude` in each new workspace terminal tab.",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )
        worktreeRootField.addBrowseFolderListener(
            "Worktree Root",
            "Parent directory for new worktrees. Leave blank for `<repo>-worktrees/` sibling.",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )

        reset()

        val panel = panel {
            row("Startup script:") {
                cell(scriptField).comment("Optional. Runs as `<script> && claude` in the workspace terminal.")
            }
            row("Worktree root:") {
                cell(worktreeRootField).comment("Blank = `<repo>-worktrees/` next to the main repo.")
            }
            row("Default merge strategy:") {
                cell(strategyBox)
            }
        }
        rootPanel = panel
        return panel
    }

    override fun isModified(): Boolean {
        return scriptField.text != settings.startupScriptPath ||
            worktreeRootField.text != settings.worktreeRoot ||
            (strategyBox.selectedItem as? MergeStrategy) != settings.defaultMergeStrategy
    }

    override fun apply() {
        settings.startupScriptPath = scriptField.text.trim()
        settings.worktreeRoot = worktreeRootField.text.trim()
        settings.defaultMergeStrategy = strategyBox.selectedItem as? MergeStrategy ?: MergeStrategy.MERGE_NO_FF
    }

    override fun reset() {
        scriptField.text = settings.startupScriptPath
        worktreeRootField.text = settings.worktreeRoot
        strategyBox.selectedItem = settings.defaultMergeStrategy
    }

    override fun disposeUIResources() {
        rootPanel = null
    }
}
