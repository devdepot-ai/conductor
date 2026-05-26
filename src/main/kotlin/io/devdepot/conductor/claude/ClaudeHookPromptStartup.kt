package io.devdepot.conductor.claude

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.settings.ConductorConfigurable
import java.nio.file.Path

/**
 * On project open: if Claude Code is installed but our hooks aren't, ask
 * the user once whether they want to install them. The "Not now" button
 * persists application-wide so the snackbar doesn't follow them around
 * across every repo.
 *
 * Fires on any git repo (trunk or workspace) when Claude is detected. The
 * hooks are user-global, so installing once benefits every project; the
 * trunk repo is often the user's first encounter with Conductor and is
 * the natural place to surface the prompt.
 */
class ClaudeHookPromptStartup : ProjectActivity {

    private val dismissKey = "io.devdepot.conductor.claude.installPromptDismissed"

    override suspend fun execute(project: Project) {
        val base = project.basePath ?: return
        if (!Git.isGitRepo(Path.of(base))) return

        val detector = ClaudeCodeDetector.get()
        // Force a fresh check on project open since the hooks file may have
        // changed since the last detection in a different IDE window.
        detector.invalidate()
        val state = detector.get()
        if (state != ClaudeCodeDetector.State.InstalledNoHooks) return

        if (PropertiesComponent.getInstance().getBoolean(dismissKey, false)) return

        notify(project)
    }

    private fun notify(project: Project) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Conductor")
            .createNotification(
                "Conductor",
                "Claude Code is installed but Conductor's hooks aren't — install to see live session status in the panel.",
                NotificationType.INFORMATION,
            )

        notification.addAction(object : NotificationAction("Install hooks") {
            override fun actionPerformed(e: AnActionEvent, notif: com.intellij.notification.Notification) {
                val r = ClaudeHookInstaller.install()
                ClaudeCodeDetector.get().invalidate()
                when (r) {
                    is ClaudeHookInstaller.Result.Ok -> notif.expire()
                    is ClaudeHookInstaller.Result.Error -> {
                        notif.setContent("Install failed: ${r.message}")
                    }
                }
            }
        })
        notification.addAction(object : NotificationAction("Open settings") {
            override fun actionPerformed(e: AnActionEvent, notif: com.intellij.notification.Notification) {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, ConductorConfigurable::class.java)
                notif.expire()
            }
        })
        notification.addAction(object : NotificationAction("Not now") {
            override fun actionPerformed(e: AnActionEvent, notif: com.intellij.notification.Notification) {
                PropertiesComponent.getInstance().setValue(dismissKey, true)
                notif.expire()
            }
        })

        notification.notify(project)
    }
}
