package io.devdepot.conductor.workspace

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * A Conductor workspace is identified by the presence of a single marker file
 * at its root: `.conductor-workspace.json`. The file doubles as the config
 * snapshot captured at creation time.
 */
object ConductorMarker {
    const val MARKER_FILE = ".conductor-workspace.json"
    private val log = Logger.getInstance(ConductorMarker::class.java)

    data class Config(
        val startupCommand: String,
        val openTerminalOnStart: Boolean,
        val defaultMergeStrategy: String,
        val createdAt: String? = null,
    )

    fun isWorkspace(path: Path): Boolean =
        Files.isRegularFile(path.resolve(MARKER_FILE))

    fun writeConfig(workspaceRoot: Path, config: Config) {
        val json = buildString {
            append("{\n")
            append("  \"startupCommand\": ").append(jsonString(config.startupCommand)).append(",\n")
            append("  \"openTerminalOnStart\": ").append(config.openTerminalOnStart).append(",\n")
            append("  \"defaultMergeStrategy\": ").append(jsonString(config.defaultMergeStrategy))
            if (config.createdAt != null) {
                append(",\n")
                append("  \"createdAt\": ").append(jsonString(config.createdAt)).append("\n")
            } else {
                append("\n")
            }
            append("}\n")
        }
        Files.writeString(workspaceRoot.resolve(MARKER_FILE), json)
    }

    fun readConfig(workspaceRoot: Path): Config? {
        val file = workspaceRoot.resolve(MARKER_FILE)
        if (!Files.isRegularFile(file)) return null
        return try {
            val text = Files.readString(file)
            Config(
                startupCommand = extractString(text, "startupCommand") ?: "",
                openTerminalOnStart = extractBool(text, "openTerminalOnStart") ?: false,
                defaultMergeStrategy = extractString(text, "defaultMergeStrategy") ?: "",
                createdAt = extractString(text, "createdAt"),
            )
        } catch (e: Throwable) {
            log.warn("Failed to read $file", e)
            null
        }
    }

    private fun jsonString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun extractString(text: String, key: String): String? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val m = regex.find(text) ?: return null
        return m.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    private fun extractBool(text: String, key: String): Boolean? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
        val m = regex.find(text) ?: return null
        return m.groupValues[1] == "true"
    }
}
