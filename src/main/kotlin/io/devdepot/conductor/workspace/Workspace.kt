package io.devdepot.conductor.workspace

import java.nio.file.Path
import java.time.Instant

/**
 * Logical Conductor workspace. Today the only implementation is a git
 * worktree ([WorktreeWorkspace]); future backings (Docker, remote, local)
 * will appear as additional subtypes.
 *
 * Callers that only need the logical view — display name, branch, working
 * directory for terminal/commands — should use this type. Implementation-
 * specific data (e.g. the worktree path) lives on the subtype.
 */
sealed class Workspace {
    abstract val name: String
    abstract val branch: String
    abstract val isCurrent: Boolean

    /**
     * Local filesystem path where the workspace lives. For a worktree this is
     * the worktree directory; for future providers it's whatever path the IDE
     * treats as the project root.
     */
    abstract val location: Path

    /**
     * When the workspace was created. Resolved from the marker file when
     * available; falls back to filesystem creation time for pre-existing
     * workspaces; null when neither source yields a timestamp.
     */
    abstract val createdAt: Instant?
}

data class WorktreeWorkspace(
    override val name: String,
    override val branch: String,
    override val isCurrent: Boolean,
    val worktreePath: Path,
    override val createdAt: Instant? = null,
) : Workspace() {
    override val location: Path get() = worktreePath
}
