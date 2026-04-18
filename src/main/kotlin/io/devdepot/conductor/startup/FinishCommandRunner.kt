package io.devdepot.conductor.startup

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import io.devdepot.conductor.ui.Notifications
import java.nio.file.Path

/**
 * Runs a user-supplied command in the Run tool window and reports the exit
 * code via [onExit]. Mirrors [StartupTaskRunner] but is callback-based so
 * callers can gate follow-up work (e.g. merge) on success.
 */
object FinishCommandRunner {
    private val log = Logger.getInstance(FinishCommandRunner::class.java)

    fun run(
        project: Project,
        cwd: Path,
        command: String,
        tabTitle: String,
        onExit: (exitCode: Int) -> Unit,
    ) {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/bash"
        val cmd = GeneralCommandLine(shell, "-lc", command)
            .withWorkDirectory(cwd.toFile())

        val handler = try {
            OSProcessHandler(cmd)
        } catch (e: Throwable) {
            log.warn("Failed to start finish command", e)
            Notifications.error(project, "Conductor", "Failed to start finish command: ${e.message}")
            return
        }

        val done = java.util.concurrent.CountDownLatch(1)
        val exitRef = java.util.concurrent.atomic.AtomicInteger(-1)
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                exitRef.set(event.exitCode)
                done.countDown()
            }
        })

        ApplicationManager.getApplication().invokeLater {
            val console = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .console
            console.attachToProcess(handler)
            val descriptor = RunContentDescriptor(
                console,
                handler,
                console.component,
                tabTitle,
            )
            RunContentManager.getInstance(project)
                .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
            ToolWindowManager.getInstance(project)
                .getToolWindow(ToolWindowId.RUN)
                ?.activate(null, false)
            handler.startNotify()

            object : Task.Backgroundable(project, "Conductor: running finish command", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    while (!done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        if (indicator.isCanceled) {
                            handler.destroyProcess()
                            return
                        }
                    }
                    val exit = exitRef.get()
                    ApplicationManager.getApplication().invokeLater {
                        onExit(exit)
                    }
                }

                override fun onCancel() {
                    if (!handler.isProcessTerminated) handler.destroyProcess()
                }
            }.queue()
        }
    }
}
