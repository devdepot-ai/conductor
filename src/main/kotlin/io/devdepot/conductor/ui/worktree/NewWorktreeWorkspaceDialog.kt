package io.devdepot.conductor.ui.worktree

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Worktree-backed workspace creation dialog. Asks for a branch name and base
 * branch — both inherently worktree/git concepts. When a second workspace
 * provider lands, factor a shared base dialog out of this one.
 */
class NewWorktreeWorkspaceDialog(
    project: Project,
    defaultName: String,
    defaultBase: String,
    private val branches: List<String>,
) : DialogWrapper(project, true) {

    var name: String = defaultName
    var baseBranch: String = defaultBase

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
            val items = branches.ifEmpty { listOf(defaultBase()) }
            comboBox(items).bindItem(
                getter = { baseBranch },
                setter = { baseBranch = it ?: defaultBase() },
            )
        }
    }

    private fun defaultBase(): String = baseBranch

    override fun doValidate(): ValidationInfo? {
        if (name.isBlank()) return ValidationInfo("Branch name is required.")
        if (name.contains(' ')) return ValidationInfo("Branch name can't contain spaces.")
        if (baseBranch.isBlank()) return ValidationInfo("Base branch is required.")
        return null
    }
}
