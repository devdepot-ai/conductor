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

object StartupTaskRunner {
    private val log = Logger.getInstance(StartupTaskRunner::class.java)

    fun run(project: Project, worktree: Path, command: String) {
        if (command.isBlank()) return

        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/bash"
        val cmd = GeneralCommandLine(shell, "-lc", command)
            .withWorkDirectory(worktree.toFile())

        val handler = try {
            OSProcessHandler(cmd)
        } catch (e: Throwable) {
            log.warn("Failed to start Conductor startup command", e)
            Notifications.error(project, "Conductor", "Failed to start startup command: ${e.message}")
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
                "Conductor startup",
            )
            RunContentManager.getInstance(project)
                .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
            ToolWindowManager.getInstance(project)
                .getToolWindow(ToolWindowId.RUN)
                ?.activate(null, false)
            handler.startNotify()

            Notifications.info(
                project,
                "Conductor",
                "Startup command running — see the Run tool window (tab: \"Conductor startup\").",
            )

            object : Task.Backgroundable(project, "Conductor: running workspace startup", true) {
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
                        if (exit == 0) {
                            Notifications.info(project, "Conductor", "Startup command finished.")
                        } else {
                            Notifications.warn(
                                project,
                                "Conductor",
                                "Startup command exited with code $exit (see Run tool window).",
                            )
                        }
                    }
                }

                override fun onCancel() {
                    if (!handler.isProcessTerminated) handler.destroyProcess()
                }
            }.queue()
        }
    }
}
