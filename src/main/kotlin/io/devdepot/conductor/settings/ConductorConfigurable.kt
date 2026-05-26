package io.devdepot.conductor.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.devdepot.conductor.claude.ClaudeCodeDetector
import io.devdepot.conductor.claude.ClaudeHookInstaller
import io.devdepot.conductor.workspace.ConductorMarker
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel

class ConductorConfigurable(private val project: Project) : Configurable {

    private val settings = ConductorSettings.get(project)
    private val startupCommandField = JBTextField()
    private val finishCommandField = JBTextField()
    private val openTerminalCheckbox = JBCheckBox("Open terminal on workspace open")
    private val terminalPositionBox = JComboBox(TerminalPosition.values())
    private val worktreeRootField = TextFieldWithBrowseButton()
    private val strategyBox = JComboBox(MergeStrategy.values())
    private val enforceCleanTreeCheckbox = JBCheckBox("Require clean working tree before finish")
        .apply { isSelected = true; isEnabled = false }

    private val localFinishCheckbox = JBCheckBox("Merge locally on finish")
    private val createPrCheckbox = JBCheckBox("Open a pull request on finish (GitHub, Bitbucket)")
    private val autoReapCheckbox = JBCheckBox("Auto-reap workspace when PR merges")
    private val prPollIntervalField = JBTextField()
    private val ghCliField = JBTextField()
    private val bbCliField = JBTextField()

    private var rootPanel: JComponent? = null
    private val workspaceMode: Boolean by lazy {
        val base = project.basePath ?: return@lazy false
        ConductorMarker.isWorkspace(Path.of(base))
    }

    private val claudeStatusLabel = JLabel()
    private val claudeInstallButton = JButton()
    private val claudeUninstallButton = JButton("Uninstall hooks")

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

        refreshClaudeSectionState()
        claudeInstallButton.addActionListener { handleClaudeInstall() }
        claudeUninstallButton.addActionListener { handleClaudeUninstall() }

        val panel = panel {
            group("Claude Code integration") {
                row {
                    cell(claudeStatusLabel)
                }
                row {
                    cell(claudeInstallButton)
                    cell(claudeUninstallButton)
                }.visible(ClaudeCodeDetector.get().get() != ClaudeCodeDetector.State.NotInstalled)
            }
            row("Startup command:") {
                cell(startupCommandField)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .comment(
                        "Runs in the background as an IDE task when the workspace opens. " +
                            "Shown as a status-bar progress item; click to view output.",
                    )
            }
            row("") {
                cell(openTerminalCheckbox)
                    .comment("When enabled, a terminal tab opens at the workspace root on open.")
            }
            row("Terminal position:") {
                cell(terminalPositionBox)
                    .align(AlignX.FILL)
                    .comment("Where the Terminal tool window is anchored when Conductor opens a workspace tab.")
            }
            row("Worktree root:") {
                cell(worktreeRootField)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .comment("Blank = a sibling folder named [repo-name]-worktrees/ next to the main repo.")
            }
            group("Finish") {
                row("Finish command:") {
                    cell(finishCommandField)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(
                            "Optional script run from the Run tool window before opening the PR. " +
                                "Non-zero exit preserves the workspace.",
                        )
                }
                row("") {
                    cell(enforceCleanTreeCheckbox)
                }
                row("") {
                    cell(createPrCheckbox)
                        .comment(
                            "Opens a PR on the forge detected from <code>origin</code>. " +
                                "Requires <code>gh</code> (GitHub) or <code>bb</code> (Bitbucket) on <code>PATH</code>.",
                        )
                }
                row("") {
                    cell(localFinishCheckbox)
                        .comment("When off, finish skips merging; the watcher reaps the workspace once the PR is merged.")
                }
                row("Default merge strategy:") {
                    cell(strategyBox).align(AlignX.FILL)
                }
                row("") {
                    cell(autoReapCheckbox)
                        .comment("Delete worktree + branch automatically once the tracked PR is merged remotely.")
                }
                row("Poll interval (s):") {
                    cell(prPollIntervalField).align(AlignX.FILL)
                }
                row("gh command:") {
                    cell(ghCliField).align(AlignX.FILL).resizableColumn()
                }
                row("bb command:") {
                    cell(bbCliField).align(AlignX.FILL).resizableColumn()
                }
            }
        }
        rootPanel = panel
        return panel
    }

    private fun buildWorkspacePanel(): JComponent {
        val basePath = project.basePath?.let { Path.of(it) }
        val snapshot = basePath?.let { ConductorMarker.readConfig(it) }
        startupCommandField.text = snapshot?.startupCommand ?: settings.startupCommand
        finishCommandField.text = settings.finishCommand
        openTerminalCheckbox.isSelected = snapshot?.openTerminalOnStart ?: settings.openTerminalOnStart
        worktreeRootField.text = settings.worktreeRoot
        strategyBox.selectedItem = snapshot?.defaultMergeStrategy
            ?.takeIf { it.isNotBlank() }
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
                cell(startupCommandField)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .enabled(false)
            }
            row("") {
                cell(openTerminalCheckbox).enabled(false)
            }
            row("Finish command:") {
                cell(finishCommandField)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .enabled(false)
            }
            row("") {
                cell(enforceCleanTreeCheckbox).enabled(false)
            }
            row("Default merge strategy:") {
                cell(strategyBox).align(AlignX.FILL).enabled(false)
            }
        }
        rootPanel = panel
        return panel
    }

    override fun isModified(): Boolean {
        if (workspaceMode) return false
        return startupCommandField.text != settings.startupCommand ||
            finishCommandField.text != settings.finishCommand ||
            openTerminalCheckbox.isSelected != settings.openTerminalOnStart ||
            (terminalPositionBox.selectedItem as? TerminalPosition) != settings.terminalPosition ||
            worktreeRootField.text != settings.worktreeRoot ||
            (strategyBox.selectedItem as? MergeStrategy) != settings.defaultMergeStrategy ||
            localFinishCheckbox.isSelected != settings.localFinishEnabled ||
            createPrCheckbox.isSelected != settings.createPrOnFinish ||
            autoReapCheckbox.isSelected != settings.autoReapOnMerge ||
            parsePollInterval() != settings.prPollIntervalSeconds ||
            ghCliField.text.trim() != settings.ghCliCommand ||
            bbCliField.text.trim() != settings.bbCliCommand
    }

    override fun apply() {
        if (workspaceMode) return
        settings.startupCommand = startupCommandField.text.trim()
        settings.finishCommand = finishCommandField.text.trim()
        settings.openTerminalOnStart = openTerminalCheckbox.isSelected
        settings.terminalPosition = terminalPositionBox.selectedItem as? TerminalPosition ?: TerminalPosition.BOTTOM
        settings.worktreeRoot = worktreeRootField.text.trim()
        settings.defaultMergeStrategy = strategyBox.selectedItem as? MergeStrategy ?: MergeStrategy.MERGE_NO_FF
        settings.localFinishEnabled = localFinishCheckbox.isSelected
        settings.createPrOnFinish = createPrCheckbox.isSelected
        settings.autoReapOnMerge = autoReapCheckbox.isSelected
        settings.prPollIntervalSeconds = parsePollInterval()
        settings.ghCliCommand = ghCliField.text.trim().ifBlank { "gh" }
        settings.bbCliCommand = bbCliField.text.trim().ifBlank { "bb" }
        settings.save()
    }

    override fun reset() {
        if (workspaceMode) return
        settings.reload()
        startupCommandField.text = settings.startupCommand
        finishCommandField.text = settings.finishCommand
        openTerminalCheckbox.isSelected = settings.openTerminalOnStart
        terminalPositionBox.selectedItem = settings.terminalPosition
        worktreeRootField.text = settings.worktreeRoot
        strategyBox.selectedItem = settings.defaultMergeStrategy
        localFinishCheckbox.isSelected = settings.localFinishEnabled
        createPrCheckbox.isSelected = settings.createPrOnFinish
        autoReapCheckbox.isSelected = settings.autoReapOnMerge
        prPollIntervalField.text = settings.prPollIntervalSeconds.toString()
        ghCliField.text = settings.ghCliCommand
        bbCliField.text = settings.bbCliCommand
    }

    override fun disposeUIResources() {
        rootPanel = null
    }

    private fun parsePollInterval(): Int =
        prPollIntervalField.text.trim().toIntOrNull()?.coerceAtLeast(30) ?: 120

    private fun refreshClaudeSectionState() {
        ClaudeCodeDetector.get().invalidate()
        val state = ClaudeCodeDetector.get().get()
        when (state) {
            ClaudeCodeDetector.State.NotInstalled -> {
                claudeStatusLabel.text =
                    "Claude Code not detected. Install from claude.com/code to enable live session status."
                claudeInstallButton.isVisible = false
                claudeUninstallButton.isVisible = false
            }
            ClaudeCodeDetector.State.InstalledNoHooks -> {
                claudeStatusLabel.text =
                    "Claude Code detected. Hooks not installed — install to see live status in the Conductor panel."
                claudeInstallButton.text = "Install hooks"
                claudeInstallButton.isVisible = true
                claudeUninstallButton.isVisible = false
            }
            ClaudeCodeDetector.State.InstalledStaleHooks -> {
                claudeStatusLabel.text =
                    "Hooks installed at an older version. Reinstall to pick up the latest behavior."
                claudeInstallButton.text = "Update hooks"
                claudeInstallButton.isVisible = true
                claudeUninstallButton.isVisible = true
            }
            ClaudeCodeDetector.State.InstalledWithHooks -> {
                claudeStatusLabel.text =
                    "Hooks installed (v${ClaudeHookInstaller.SCRIPT_VERSION}). Conductor will show live Claude status."
                claudeInstallButton.text = "Reinstall hooks"
                claudeInstallButton.isVisible = true
                claudeUninstallButton.isVisible = true
            }
        }
    }

    private fun handleClaudeInstall() {
        val r = ClaudeHookInstaller.install()
        ClaudeCodeDetector.get().invalidate()
        when (r) {
            is ClaudeHookInstaller.Result.Ok -> {
                refreshClaudeSectionState()
                Messages.showInfoMessage(
                    project,
                    "Claude hooks installed at ${ClaudeHookInstaller.hookScriptPath}.",
                    "Conductor",
                )
            }
            is ClaudeHookInstaller.Result.Error -> {
                Messages.showErrorDialog(project, "Failed to install hooks: ${r.message}", "Conductor")
            }
        }
    }

    private fun handleClaudeUninstall() {
        val confirm = Messages.showYesNoDialog(
            project,
            "Remove Conductor's Claude Code hooks from ~/.claude/settings.json?",
            "Conductor",
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return
        val r = ClaudeHookInstaller.uninstall()
        ClaudeCodeDetector.get().invalidate()
        when (r) {
            is ClaudeHookInstaller.Result.Ok -> refreshClaudeSectionState()
            is ClaudeHookInstaller.Result.Error ->
                Messages.showErrorDialog(project, "Failed to uninstall hooks: ${r.message}", "Conductor")
        }
    }
}
