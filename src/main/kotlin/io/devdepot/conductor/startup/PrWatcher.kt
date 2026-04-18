package io.devdepot.conductor.startup

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import io.devdepot.conductor.forge.Forge
import io.devdepot.conductor.forge.PrClientFactory
import io.devdepot.conductor.forge.PrOutcome
import io.devdepot.conductor.forge.PrRemoteState
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.ui.Notifications
import io.devdepot.conductor.workspace.ConductorMarker
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService
import io.devdepot.conductor.workspace.WorktreeWorkspace
import java.time.Instant

/**
 * Polls forge APIs for tracked PRs and updates markers when merge state
 * changes. Trunk-scoped — running on workspace windows as well would mean
 * duplicate polling for no benefit. The watcher itself is lazy: a tick is
 * a no-op when no workspace marker carries a PR in the `open` state.
 */
@Service(Service.Level.PROJECT)
class PrWatcher(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PrWatcher::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    @Volatile private var started: Boolean = false

    fun start() {
        if (started) return
        started = true
        scheduleNext()
    }

    /**
     * Poll once, then reschedule. Used by Conductor.ReapMergedWorkspace to
     * kick an immediate check without waiting for the next tick.
     */
    fun pollNow() {
        alarm.cancelAllRequests()
        alarm.addRequest({ tickAndReschedule() }, 0)
    }

    private fun scheduleNext() {
        if (project.isDisposed) return
        val delayMs = ConductorSettings.get(project).prPollIntervalSeconds.toLong() * 1000
        alarm.addRequest({ tickAndReschedule() }, delayMs)
    }

    private fun tickAndReschedule() {
        try {
            tick()
        } catch (t: Throwable) {
            log.warn("PrWatcher tick failed", t)
        } finally {
            scheduleNext()
        }
    }

    private fun tick() {
        if (project.isDisposed) return
        val service = WorkspaceService.get(project)
        val settings = ConductorSettings.get(project)
        val workspaces = service.list()
        if (workspaces.isEmpty()) return

        var anyChanged = false
        for (workspace in workspaces) {
            if (workspace !is WorktreeWorkspace) continue
            val marker = ConductorMarker.readConfig(workspace.location) ?: continue
            val pr = marker.pr ?: continue
            if (pr.state != "open") continue

            val forge = Forge.fromId(pr.forge)
            val client = PrClientFactory.forForge(project, forge) ?: continue

            val remote = when (val r = client.fetchState(workspace.location, pr.number)) {
                is PrOutcome.Ok -> r.value
                is PrOutcome.Err -> {
                    log.info("PR ${pr.number} poll failed: ${r.message}")
                    continue
                }
            }

            val now = Instant.now().toString()
            if (remote.state == "merged") {
                ConductorMarker.writePrState(
                    workspace.location,
                    pr.copy(state = "merged", mergedAt = remote.mergedAt ?: now, lastCheckedAt = now),
                )
                anyChanged = true
                Notifications.info(
                    project,
                    "Conductor",
                    "PR #${pr.number} merged (${workspace.branch}).",
                )
                if (settings.autoReapOnMerge) {
                    reapWorkspace(service, workspace)
                }
            } else if (remote.state != pr.state) {
                ConductorMarker.writePrState(
                    workspace.location,
                    pr.copy(state = remote.state, lastCheckedAt = now),
                )
                anyChanged = true
            } else {
                ConductorMarker.writePrState(
                    workspace.location,
                    pr.copy(lastCheckedAt = now),
                )
            }
        }
        if (anyChanged) service.invalidate()
    }

    private fun reapWorkspace(service: WorkspaceService, workspace: Workspace) {
        when (val r = service.reap(workspace, deleteBranch = true)) {
            is WorkspaceService.FinishResult.Ok ->
                Notifications.info(project, "Conductor", r.message)
            is WorkspaceService.FinishResult.Error ->
                Notifications.error(project, "Conductor", r.message)
            else -> Unit
        }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }

    companion object {
        fun get(project: Project): PrWatcher = project.service()
    }
}
