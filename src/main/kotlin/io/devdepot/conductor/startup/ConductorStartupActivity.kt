package io.devdepot.conductor.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.ide.ClaudeTerminalLauncher
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.ConductorMarker
import java.nio.file.Path

class ConductorStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val basePath = project.basePath ?: return
        val root = Path.of(basePath)
        if (!ConductorMarker.isPresent(root)) return
        if (!Git.isWorktree(root)) return

        val name = root.fileName?.toString() ?: "workspace"
        val settings = ConductorSettings.get(project)
        ClaudeTerminalLauncher.launch(project, name, settings.startupScriptPath)

        val branch = Git.exec(listOf("rev-parse", "--abbrev-ref", "HEAD"), root)
            .stdout.ifBlank { name }
        Notifications.info(project, "Conductor", "AI Workspace `$branch` ready.")
    }
}
