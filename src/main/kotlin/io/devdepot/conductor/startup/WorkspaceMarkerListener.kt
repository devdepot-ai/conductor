package io.devdepot.conductor.startup

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.devdepot.conductor.workspace.ConductorMarker

/**
 * Watches for creation/deletion/rename of the `.conductor-workspace.json`
 * marker file anywhere in the VFS, and nudges the action system to re-run
 * `update()`. That flips the toolbar button's label immediately across all
 * open IDE windows — no restart needed.
 */
class WorkspaceMarkerListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val touched = events.any { it.marker() == ConductorMarker.MARKER_FILE }
        if (touched) ActivityTracker.getInstance().inc()
    }

    private fun VFileEvent.marker(): String? = when (this) {
        is VFileCreateEvent -> childName
        is VFileDeleteEvent -> file.name
        is VFilePropertyChangeEvent -> file.name
        else -> file?.name
    }
}
