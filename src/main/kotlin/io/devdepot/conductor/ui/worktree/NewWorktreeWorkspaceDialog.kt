package io.devdepot.conductor.ui.worktree

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.layout.selected
import javax.swing.JComponent

/**
 * Worktree-backed workspace creation dialog. Two modes:
 *  - Create new branch (default): pick a name and a base ref — local or
 *    remote-tracking (e.g. `origin/feature`); runs `git worktree add -b`.
 *  - Check out an existing branch: pick an existing local or remote branch
 *    from a dropdown; no new branch is created (a remote ref gets a local
 *    tracking branch).
 *
 * When a second workspace provider lands, factor a shared base dialog out of
 * this one.
 */
class NewWorktreeWorkspaceDialog(
    project: Project,
    defaultName: String,
    defaultBase: String,
    branches: List<String>,
    existingBranches: List<String>,
    hasStartupCommand: Boolean = true,
    claudeEnabled: Boolean = false,
    defaultStartupCommand: String = "",
    preselectExistingBranch: String? = null,
) : DialogWrapper(project, true) {

    var name: String = defaultName
    val baseBranch: String get() = baseField.text.trim()
    val existingBranch: String get() =
        (existingBranchCombo.editor.item ?: existingBranchCombo.selectedItem)?.toString()?.trim().orEmpty()
    val useExistingBranch: Boolean get() = useExistingCheckbox.isSelected
    val runStartupCommand: Boolean get() = runStartupCheckbox.isSelected

    /** The startup command to run, honoring any per-workspace override. */
    val startupCommand: String get() = startupCommandField.text.trim()

    /** First message to seed the Claude session with; blank when unused. */
    val initialPrompt: String get() = if (claudeAvailable) promptArea.text.trim() else ""

    private val startupCommandConfigured: Boolean = hasStartupCommand
    private val claudeAvailable: Boolean = claudeEnabled
    private val knownExisting: Set<String> = existingBranches.toSet()

    private val useExistingCheckbox = JBCheckBox("Check out an existing branch").apply {
        isSelected = !preselectExistingBranch.isNullOrBlank()
    }

    private val baseField: TextFieldWithAutoCompletion<String> =
        TextFieldWithAutoCompletion.create(project, branches, false, defaultBase)

    private val existingBranchCombo: ComboBox<String> =
        ComboBox(existingBranches.toTypedArray()).apply {
            isEditable = true
            selectedItem = preselectExistingBranch?.takeIf { it.isNotBlank() }
        }

    private val runStartupCheckbox = JBCheckBox("Run startup command after opening", hasStartupCommand).apply {
        isEnabled = hasStartupCommand
    }

    private val startupCommandField = JBTextField(defaultStartupCommand)

    private val promptArea: JBTextArea = JBTextArea(4, 32).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "New AI Workspace"
        setOKButtonText("Create")
        init()
    }

    private fun newBranchMode(): ComponentPredicate = object : ComponentPredicate() {
        override fun invoke(): Boolean = !useExistingCheckbox.isSelected
        override fun addListener(listener: (Boolean) -> Unit) {
            useExistingCheckbox.addItemListener { listener(!useExistingCheckbox.isSelected) }
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        val existingMode = useExistingCheckbox.selected
        val newMode = newBranchMode()
        row {
            cell(useExistingCheckbox)
                .comment("Open a worktree on an existing branch instead of creating a new one.")
        }
        row("Branch name:") {
            val field = textField()
                .bindText(::name)
                .focused()
            field.component.columns = 32
        }.visibleIf(newMode)
        row("Base branch:") {
            cell(baseField)
                .align(AlignX.FILL)
                .comment("Local or remote-tracking branch (e.g. <code>origin/feature</code>).")
        }.visibleIf(newMode)
        row("Branch:") {
            cell(existingBranchCombo)
                .align(AlignX.FILL)
                .comment("Existing local or remote branch to check out.")
        }.visibleIf(existingMode)
        row {
            cell(runStartupCheckbox)
                .comment(
                    if (startupCommandConfigured) {
                        "Uncheck to open the workspace without running its configured startup command."
                    } else {
                        "No startup command configured (see Settings → Conductor)."
                    },
                )
        }
        if (startupCommandConfigured) {
            row("Startup command:") {
                cell(startupCommandField)
                    .align(AlignX.FILL)
                    .enabledIf(runStartupCheckbox.selected)
                    .comment("Runs in the workspace terminal on open. Edit to override for this workspace only.")
            }
        }
        if (claudeAvailable) {
            row("Message to Claude:") {
                cell(JBScrollPane(promptArea))
                    .align(AlignX.FILL)
                    .comment("Optional. Sent as Claude's first prompt when the workspace opens.")
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (useExistingBranch) {
            if (existingBranch.isBlank()) return ValidationInfo("Select a branch to check out.", existingBranchCombo)
            if (existingBranch !in knownExisting) {
                return ValidationInfo("Unknown branch: $existingBranch", existingBranchCombo)
            }
            return null
        }
        if (name.isBlank()) return ValidationInfo("Branch name is required.")
        if (name.contains(' ')) return ValidationInfo("Branch name can't contain spaces.")
        if (baseBranch.isBlank()) return ValidationInfo("Base branch is required.", baseField)
        return null
    }
}
