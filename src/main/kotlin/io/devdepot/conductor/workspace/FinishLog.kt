package io.devdepot.conductor.workspace

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import io.devdepot.conductor.forge.CliResult
import io.devdepot.conductor.git.GitResult

/**
 * Streams the commands and output of a Finish run to a tab in the Run tool
 * window so users can see what git/gh actually did. Lazy: the tab is only
 * created on first write, so nothing pops up if the caller never logs.
 */
class FinishLog(private val project: Project, private val tabTitle: String) {

    private val handler: ProcessHandler = NopProcessHandler()
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance()
        .createBuilder(project)
        .console
    private var shown: Boolean = false

    init {
        console.attachToProcess(handler)
    }

    fun section(title: String) {
        writeLine("", ConsoleViewContentType.SYSTEM_OUTPUT)
        writeLine("==== $title ====", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    fun info(line: String) = writeLine(line, ConsoleViewContentType.NORMAL_OUTPUT)

    fun error(line: String) = writeLine(line, ConsoleViewContentType.ERROR_OUTPUT)

    fun command(label: String, exitCode: Int, stdout: String, stderr: String) {
        writeLine("$ $label", ConsoleViewContentType.SYSTEM_OUTPUT)
        if (stdout.isNotBlank()) writeLine(stdout, ConsoleViewContentType.NORMAL_OUTPUT)
        if (stderr.isNotBlank()) writeLine(stderr, ConsoleViewContentType.ERROR_OUTPUT)
        if (exitCode == 0) {
            writeLine("[exit 0]", ConsoleViewContentType.SYSTEM_OUTPUT)
        } else {
            writeLine("[exit $exitCode]", ConsoleViewContentType.ERROR_OUTPUT)
        }
    }

    fun command(label: String, r: GitResult) = command(label, r.exitCode, r.stdout, r.stderr)

    fun command(label: String, r: CliResult) = command(label, r.exitCode, r.stdout, r.stderr)

    /**
     * Mark the run as terminated so the console tab shows its "process
     * finished" state. Safe to call more than once.
     */
    fun done() {
        if (!handler.isProcessTerminated) handler.destroyProcess()
    }

    private fun writeLine(text: String, type: ConsoleViewContentType) {
        ensureShown()
        console.print(text + "\n", type)
    }

    private fun ensureShown() {
        if (shown) return
        shown = true
        ApplicationManager.getApplication().invokeLater {
            val descriptor = RunContentDescriptor(console, handler, console.component, tabTitle)
            RunContentManager.getInstance(project)
                .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
            ToolWindowManager.getInstance(project)
                .getToolWindow(ToolWindowId.RUN)
                ?.activate(null, false)
            handler.startNotify()
        }
    }
}
