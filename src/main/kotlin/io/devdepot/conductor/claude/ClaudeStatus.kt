package io.devdepot.conductor.claude

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.streams.toList

/**
 * Aggregated Claude Code status for a single workspace.
 *
 * Computed from per-session files under `<workspace>/.conductor/claude/`,
 * which are written by the hook script (see resources/scripts/).
 */
enum class ClaudeStatus { Working, NeedsAttention, Idle, NotRunning }

data class ClaudeSessionStatus(
    val sessionId: String,
    val state: ClaudeStatus,
    val lastEvent: String,
    val lastEventAt: Instant?,
)

data class ClaudeWorkspaceStatus(val sessions: List<ClaudeSessionStatus>) {
    val aggregate: ClaudeStatus = when {
        sessions.isEmpty() -> ClaudeStatus.NotRunning
        sessions.any { it.state == ClaudeStatus.NeedsAttention } -> ClaudeStatus.NeedsAttention
        sessions.any { it.state == ClaudeStatus.Working } -> ClaudeStatus.Working
        else -> ClaudeStatus.Idle
    }

    companion object {
        val EMPTY = ClaudeWorkspaceStatus(emptyList())
    }
}

object ClaudeStatusReader {

    const val STATE_DIR = ".conductor/claude"

    /**
     * Sessions whose last event is older than this are filtered out. Backs
     * up the on-close cleanup for cases where Claude was killed ungracefully
     * (SIGKILL, OOM, container teardown) and never wrote SessionEnd.
     */
    private val STALE_AFTER = java.time.Duration.ofHours(1)

    private val log = Logger.getInstance(ClaudeStatusReader::class.java)

    fun read(workspaceRoot: Path): ClaudeWorkspaceStatus {
        val dir = workspaceRoot.resolve(".conductor").resolve("claude")
        if (!Files.isDirectory(dir)) return ClaudeWorkspaceStatus.EMPTY
        val cutoff = Instant.now().minus(STALE_AFTER)
        val sessions = try {
            Files.list(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .toList()
                    .mapNotNull { parse(it) }
                    .filter { (it.lastEventAt ?: Instant.MIN) >= cutoff }
            }
        } catch (e: Throwable) {
            log.debug("Failed to list $dir: ${e.message}")
            emptyList()
        }
        return ClaudeWorkspaceStatus(sessions)
    }

    private fun parse(file: Path): ClaudeSessionStatus? {
        return try {
            val text = Files.readString(file)
            if (text.isBlank()) return null
            val obj = JsonParser.parseString(text) as? JsonObject ?: return null
            val sessionId = obj.get("sessionId")?.asString
                ?: file.fileName.toString().removeSuffix(".json")
            val stateStr = obj.get("state")?.asString ?: "idle"
            val state = when (stateStr) {
                "working" -> ClaudeStatus.Working
                "needs_attention" -> ClaudeStatus.NeedsAttention
                "idle" -> ClaudeStatus.Idle
                else -> ClaudeStatus.Idle
            }
            val event = obj.get("lastEvent")?.asString ?: ""
            val at = obj.get("lastEventAt")?.asString
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ClaudeSessionStatus(sessionId, state, event, at)
        } catch (e: Throwable) {
            log.debug("Failed to parse $file: ${e.message}")
            null
        }
    }
}
