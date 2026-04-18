package io.devdepot.conductor.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.ide.WorkspaceTerminalLauncher
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.ConductorMarker
import io.devdepot.conductor.workspace.WorkspaceService
import java.nio.file.Path

class ConductorStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.messageBus.connect()
            .subscribe(VirtualFileManager.VFS_CHANGES, WorkspaceMarkerListener())

        WorkspaceService.get(project).warmCache()

        val basePath = project.basePath ?: return
        val root = Path.of(basePath)
        if (!ConductorMarker.isWorkspace(root)) return

        val config = ConductorMarker.readConfig(root)
        val name = config?.name?.takeIf { it.isNotBlank() }
            ?: root.fileName?.toString()
            ?: "workspace"
        val settings = ConductorSettings.get(project)
        val startupCommand = config?.startupCommand ?: settings.startupCommand
        val openTerminal = config?.openTerminalOnStart ?: settings.openTerminalOnStart

        if (startupCommand.isNotBlank()) {
            StartupTaskRunner.run(project, root, startupCommand)
        }
        if (openTerminal) {
            WorkspaceTerminalLauncher.launch(project, name)
        }

        val branch = Git.exec(listOf("rev-parse", "--abbrev-ref", "HEAD"), root)
            .stdout.ifBlank { name }
        Notifications.info(project, "Conductor", "AI Workspace `$branch` ready.")
    }
}
