package io.devdepot.conductor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import io.devdepot.conductor.settings.MergeStrategy
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JRadioButton

class FinishWorkspaceDialog(
    project: Project,
    branch: String,
    defaultBase: String,
    private val branches: List<String>,
    defaultStrategy: MergeStrategy,
) : DialogWrapper(project, true) {

    var strategy: MergeStrategy = defaultStrategy
    var baseBranch: String = defaultBase
    var deleteBranch: Boolean = true
    var discard: Boolean = false

    private val buttons = mutableMapOf<MergeStrategy, JRadioButton>()
    private var discardButton: JRadioButton? = null

    init {
        title = "Finish `$branch`"
        setOKButtonText("Finish")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        val group = ButtonGroup()
        row("Strategy:") { }
        for (s in MergeStrategy.values()) {
            row {
                val rb = radioButton(s.label).component
                rb.isSelected = s == strategy
                buttons[s] = rb
                group.add(rb)
                rb.addActionListener { if (rb.isSelected) { strategy = s; discard = false; updateDeleteState() } }
            }
        }
        row {
            val rb = radioButton("discard").component
            rb.isSelected = false
            discardButton = rb
            group.add(rb)
            rb.addActionListener { if (rb.isSelected) { discard = true; updateDeleteState() } }
        }

        row("Base branch:") {
            val items = branches.ifEmpty { listOf(baseBranch) }
            comboBox(items).bindItem(
                getter = { baseBranch },
                setter = { baseBranch = it ?: baseBranch },
            )
        }
        row {
            checkBox("Delete branch after merge")
                .bindSelected(::deleteBranch)
        }
    }

    private fun updateDeleteState() {
        // Discard always removes the branch; the checkbox becomes irrelevant.
        // We leave the field alone (caller consults `discard`) but nudge UX.
        deleteBranch = deleteBranch || discard
    }

    override fun doValidate(): ValidationInfo? {
        if (!discard && baseBranch.isBlank()) return ValidationInfo("Base branch is required.")
        return null
    }
}
