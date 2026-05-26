package io.devdepot.conductor.ui.worktree

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.TextFieldWithAutoCompletion
import javax.swing.JComponent

/**
 * Worktree-backed workspace creation dialog. Asks for a branch name and base
 * ref — local or remote-tracking (e.g. `origin/feature`); `git worktree add -b`
 * accepts either. When a second workspace provider lands, factor a shared base
 * dialog out of this one.
 */
class NewWorktreeWorkspaceDialog(
    project: Project,
    defaultName: String,
    defaultBase: String,
    branches: List<String>,
    hasStartupCommand: Boolean = true,
) : DialogWrapper(project, true) {

    var name: String = defaultName
    val baseBranch: String get() = baseField.text.trim()
    var runStartupCommand: Boolean = hasStartupCommand
        private set

    private val startupCommandConfigured: Boolean = hasStartupCommand

    private val baseField: TextFieldWithAutoCompletion<String> =
        TextFieldWithAutoCompletion.create(project, branches, false, defaultBase)

    init {
        title = "New AI Workspace"
        setOKButtonText("Create")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Branch name:") {
            val field = textField()
                .bindText(::name)
                .focused()
            field.component.columns = 32
        }
        row("Base branch:") {
            cell(baseField)
                .align(AlignX.FILL)
                .comment("Local or remote-tracking branch (e.g. <code>origin/feature</code>).")
        }
        row {
            checkBox("Run startup command after opening")
                .bindSelected(::runStartupCommand)
                .enabled(startupCommandConfigured)
                .comment(
                    if (startupCommandConfigured) {
                        "Uncheck to open the workspace without running its configured startup command."
                    } else {
                        "No startup command configured (see Settings → Conductor)."
                    },
                )
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (name.isBlank()) return ValidationInfo("Branch name is required.")
        if (name.contains(' ')) return ValidationInfo("Branch name can't contain spaces.")
        if (baseBranch.isBlank()) return ValidationInfo("Base branch is required.", baseField)
        return null
    }
}
