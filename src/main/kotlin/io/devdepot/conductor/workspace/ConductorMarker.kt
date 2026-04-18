package io.devdepot.conductor.workspace

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * A Conductor workspace is identified by the presence of a single marker file
 * at its root: `.conductor-workspace.json`. The file doubles as the config
 * snapshot captured at creation time. Unknown JSON keys are preserved on
 * write-back so other tools can store their own data alongside ours.
 */
object ConductorMarker {
    const val MARKER_FILE = ".conductor-workspace.json"
    private val log = Logger.getInstance(ConductorMarker::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class Config(
        val startupCommand: String,
        val openTerminalOnStart: Boolean,
        val defaultMergeStrategy: String,
        val createdAt: String? = null,
        val name: String? = null,
        val pr: PrState? = null,
    )

    data class PrState(
        val forge: String,
        val number: Int,
        val url: String,
        val baseBranch: String,
        val headBranch: String,
        val state: String,
        val lastCheckedAt: String,
        val mergedAt: String? = null,
    )

    fun isWorkspace(path: Path): Boolean =
        Files.isRegularFile(path.resolve(MARKER_FILE))

    fun writeConfig(workspaceRoot: Path, config: Config) {
        val existing = readRawObject(workspaceRoot) ?: JsonObject()
        writeRawObject(workspaceRoot, mergeConfig(existing, config))
    }

    fun readConfig(workspaceRoot: Path): Config? {
        val root = readRawObject(workspaceRoot) ?: return null
        return try {
            Config(
                startupCommand = root.stringOr("startupCommand", ""),
                openTerminalOnStart = root.boolOr("openTerminalOnStart", false),
                defaultMergeStrategy = root.stringOr("defaultMergeStrategy", ""),
                createdAt = root.stringOrNull("createdAt"),
                name = root.stringOrNull("name"),
                pr = root.getAsJsonObject("pr")?.let(::parsePrState),
            )
        } catch (e: Throwable) {
            log.warn("Failed to parse ${workspaceRoot.resolve(MARKER_FILE)}", e)
            null
        }
    }

    fun writePrState(workspaceRoot: Path, pr: PrState) {
        val existing = readRawObject(workspaceRoot) ?: JsonObject()
        existing.add("pr", serializePrState(pr))
        writeRawObject(workspaceRoot, existing)
    }

    fun clearPrState(workspaceRoot: Path) {
        val existing = readRawObject(workspaceRoot) ?: return
        if (!existing.has("pr")) return
        existing.remove("pr")
        writeRawObject(workspaceRoot, existing)
    }

    private fun mergeConfig(existing: JsonObject, config: Config): JsonObject {
        existing.addProperty("startupCommand", config.startupCommand)
        existing.addProperty("openTerminalOnStart", config.openTerminalOnStart)
        existing.addProperty("defaultMergeStrategy", config.defaultMergeStrategy)
        if (config.createdAt != null) existing.addProperty("createdAt", config.createdAt)
        if (config.name != null) existing.addProperty("name", config.name)
        if (config.pr != null) existing.add("pr", serializePrState(config.pr))
        return existing
    }

    private fun serializePrState(pr: PrState): JsonObject = JsonObject().apply {
        addProperty("forge", pr.forge)
        addProperty("number", pr.number)
        addProperty("url", pr.url)
        addProperty("baseBranch", pr.baseBranch)
        addProperty("headBranch", pr.headBranch)
        addProperty("state", pr.state)
        addProperty("lastCheckedAt", pr.lastCheckedAt)
        if (pr.mergedAt != null) addProperty("mergedAt", pr.mergedAt)
    }

    private fun parsePrState(obj: JsonObject): PrState = PrState(
        forge = obj.stringOr("forge", ""),
        number = obj.intOr("number", 0),
        url = obj.stringOr("url", ""),
        baseBranch = obj.stringOr("baseBranch", ""),
        headBranch = obj.stringOr("headBranch", ""),
        state = obj.stringOr("state", "open"),
        lastCheckedAt = obj.stringOr("lastCheckedAt", ""),
        mergedAt = obj.stringOrNull("mergedAt"),
    )

    private fun readRawObject(workspaceRoot: Path): JsonObject? {
        val file = workspaceRoot.resolve(MARKER_FILE)
        if (!Files.isRegularFile(file)) return null
        return try {
            val text = Files.readString(file)
            if (text.isBlank()) return null
            JsonParser.parseString(text) as? JsonObject
        } catch (e: Throwable) {
            log.warn("Failed to read $file", e)
            null
        }
    }

    private fun writeRawObject(workspaceRoot: Path, obj: JsonObject) {
        val file = workspaceRoot.resolve(MARKER_FILE)
        try {
            Files.writeString(file, gson.toJson(obj) + "\n")
        } catch (e: Throwable) {
            log.warn("Failed to write $file", e)
        }
    }

    private fun JsonObject.stringOr(key: String, default: String): String {
        val el = get(key) as? JsonPrimitive ?: return default
        return if (el.isString) el.asString else default
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val el = get(key) as? JsonPrimitive ?: return null
        return if (el.isString) el.asString else null
    }

    private fun JsonObject.boolOr(key: String, default: Boolean): Boolean {
        val el = get(key) as? JsonPrimitive ?: return default
        return if (el.isBoolean) el.asBoolean else default
    }

    private fun JsonObject.intOr(key: String, default: Int): Int {
        val el = get(key) as? JsonPrimitive ?: return default
        return if (el.isNumber) el.asInt else default
    }
}
