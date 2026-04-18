package io.devdepot.conductor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import io.devdepot.conductor.forge.Forge
import io.devdepot.conductor.settings.MergeStrategy
import javax.swing.JCheckBox
import javax.swing.JComponent

class FinishWorkspaceDialog(
    project: Project,
    private val branch: String,
    defaultBase: String,
    private val branches: List<String>,
    defaultStrategy: MergeStrategy,
    private val forge: Forge,
    private val hasFinishCommand: Boolean,
    defaultRunFinishCommand: Boolean,
    defaultOpenPr: Boolean,
    defaultMergeLocally: Boolean,
    defaultPrTitle: String,
    defaultPrBody: String,
) : DialogWrapper(project, true) {

    var runFinishCommand: Boolean = defaultRunFinishCommand && hasFinishCommand
    var openPr: Boolean = defaultOpenPr && forge != Forge.NONE
    var mergeLocally: Boolean = defaultMergeLocally
    var prTitle: String = defaultPrTitle
    var prBody: String = defaultPrBody
    var strategy: MergeStrategy = defaultStrategy
    var baseBranch: String = defaultBase
    var deleteBranch: Boolean = true
    var discard: Boolean = false

    private val prTitleField = JBTextField(defaultPrTitle)
    private val prBodyArea = JBTextArea(defaultPrBody, 6, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val openPrCheckbox = JCheckBox("Open pull request", openPr).apply {
        isEnabled = forge != Forge.NONE
        if (forge == Forge.NONE) toolTipText = "No forge detected for origin remote."
    }
    private val mergeLocallyCheckbox = JCheckBox("Also merge locally", mergeLocally)

    init {
        title = "Finish `$branch`"
        setOKButtonText("Finish")
        openPrCheckbox.addActionListener { openPr = openPrCheckbox.isSelected }
        mergeLocallyCheckbox.addActionListener { mergeLocally = mergeLocallyCheckbox.isSelected }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("Finish `$branch`")
            val badge = when (forge) {
                Forge.GITHUB -> "will open GitHub PR"
                Forge.BITBUCKET -> "will open Bitbucket PR"
                Forge.NONE -> "no forge detected"
            }
            comment(badge)
        }

        if (hasFinishCommand) {
            row {
                checkBox("Run finish command first")
                    .bindSelected(::runFinishCommand)
            }
        }

        row { cell(openPrCheckbox) }

        indent {
            row("Title:") {
                cell(prTitleField)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .enabledIf(openPrCheckbox.selected)
            }
            row("Body:") {
                cell(prBodyArea)
                    .align(AlignX.FILL)
                    .align(AlignY.FILL)
                    .resizableColumn()
                    .enabledIf(openPrCheckbox.selected)
            }
        }

        row { cell(mergeLocallyCheckbox) }

        indent {
            row("Base branch:") {
                val items = branches.ifEmpty { listOf(baseBranch) }
                comboBox(items).bindItem(
                    getter = { baseBranch },
                    setter = { baseBranch = it ?: baseBranch },
                ).enabledIf(mergeLocallyCheckbox.selected)
            }
            row("Strategy:") {
                comboBox(MergeStrategy.values().toList()).bindItem(
                    getter = { strategy },
                    setter = { strategy = it ?: strategy },
                ).enabledIf(mergeLocallyCheckbox.selected)
            }
            row {
                checkBox("Delete branch after merge")
                    .bindSelected(::deleteBranch)
                    .enabledIf(mergeLocallyCheckbox.selected)
            }
        }

        row {
            link("Discard instead…") {
                discard = true
                doOKAction()
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        prTitle = prTitleField.text
        prBody = prBodyArea.text
        openPr = openPrCheckbox.isSelected
        mergeLocally = mergeLocallyCheckbox.isSelected

        if (discard) return null
        if (!openPr && !mergeLocally) {
            return ValidationInfo(
                "Nothing to do — enable 'Open pull request', 'Also merge locally', or click 'Discard instead'.",
            )
        }
        if (openPr && prTitle.isBlank()) {
            return ValidationInfo("PR title is required.", prTitleField)
        }
        if (mergeLocally && baseBranch.isBlank()) {
            return ValidationInfo("Base branch is required.")
        }
        return null
    }

    override fun doOKAction() {
        prTitle = prTitleField.text
        prBody = prBodyArea.text
        openPr = openPrCheckbox.isSelected
        mergeLocally = mergeLocallyCheckbox.isSelected
        super.doOKAction()
    }
}
