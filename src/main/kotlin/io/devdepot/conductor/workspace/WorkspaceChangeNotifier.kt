package io.devdepot.conductor.workspace

import com.intellij.util.messages.Topic

/**
 * Broadcast on the project message bus whenever the workspace snapshot
 * changes (create / finish / discard / explicit refresh). Subscribers
 * (e.g. the Conductor tool window) rebuild their view; publishers should
 * go through [WorkspaceService.invalidate] rather than publishing directly.
 */
fun interface WorkspaceChangeNotifier {
    fun changed()
}

object WorkspaceTopics {
    @JvmField
    val CHANGED: Topic<WorkspaceChangeNotifier> =
        Topic.create("Conductor workspace changed", WorkspaceChangeNotifier::class.java)
}
