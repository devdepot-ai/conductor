package io.devdepot.conductor.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifications {
    private const val GROUP_ID = "Conductor"

    fun info(project: Project?, title: String, message: String) =
        notify(project, title, message, NotificationType.INFORMATION)

    fun warn(project: Project?, title: String, message: String) =
        notify(project, title, message, NotificationType.WARNING)

    fun error(project: Project?, title: String, message: String) =
        notify(project, title, message, NotificationType.ERROR)

    private fun notify(project: Project?, title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, message, type)
            .notify(project)
    }
}
