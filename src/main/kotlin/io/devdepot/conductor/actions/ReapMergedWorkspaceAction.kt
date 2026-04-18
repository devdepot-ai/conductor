package io.devdepot.conductor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import io.devdepot.conductor.startup.PrWatcher

/**
 * Forces an immediate PR-watcher tick. Handy for impatient users or when
 * debugging — normal behaviour is to wait for the scheduled poll.
 */
class ReapMergedWorkspaceAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && ActionContext.isTrunk(e.project!!)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PrWatcher.get(project).pollNow()
    }
}
