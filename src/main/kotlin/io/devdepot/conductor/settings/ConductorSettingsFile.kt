package io.devdepot.conductor.settings

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads and writes Conductor trunk settings at `<repoRoot>/.conductor/settings.json`.
 * Unknown keys in an existing file are preserved on write-back so other IDE
 * integrations can store their own data alongside ours.
 */
object ConductorSettingsFile {
    const val DIR_NAME = ".conductor"
    const val FILE_NAME = "settings.json"

    private val log = Logger.getInstance(ConductorSettingsFile::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class LoadResult(val state: ConductorSettings.State, val extraKeys: JsonObject)

    fun path(repoRoot: Path): Path = repoRoot.resolve(DIR_NAME).resolve(FILE_NAME)

    fun read(repoRoot: Path): LoadResult? {
        val file = path(repoRoot)
        if (!Files.isRegularFile(file)) return null
        return try {
            val text = Files.readString(file)
            val root = JsonParser.parseString(text) as? JsonObject ?: return null
            val state = ConductorSettings.State(
                startupCommand = root.stringOr("startupCommand", ""),
                finishCommand = root.stringOr("finishCommand", ""),
                openTerminalOnStart = root.boolOr("openTerminalOnStart", false),
                worktreeRoot = root.stringOr("worktreeRoot", ""),
                defaultMergeStrategy = root.stringOr("defaultMergeStrategy", MergeStrategy.MERGE_NO_FF.id),
                branchPrefix = root.stringOr("branchPrefix", "wt/"),
                enforceCleanTreeOnFinish = root.boolOr("enforceCleanTreeOnFinish", true),
            )
            val extras = JsonObject()
            for ((k, v) in root.entrySet()) {
                if (k !in KNOWN_KEYS) extras.add(k, v)
            }
            LoadResult(state, extras)
        } catch (e: Throwable) {
            log.warn("Failed to read $file", e)
            null
        }
    }

    fun write(repoRoot: Path, state: ConductorSettings.State, extraKeys: JsonObject = JsonObject()) {
        val dir = repoRoot.resolve(DIR_NAME)
        val file = dir.resolve(FILE_NAME)
        try {
            Files.createDirectories(dir)
            val root = JsonObject().apply {
                addProperty("startupCommand", state.startupCommand)
                addProperty("finishCommand", state.finishCommand)
                addProperty("openTerminalOnStart", state.openTerminalOnStart)
                addProperty("worktreeRoot", state.worktreeRoot)
                addProperty("defaultMergeStrategy", state.defaultMergeStrategy)
                addProperty("branchPrefix", state.branchPrefix)
                addProperty("enforceCleanTreeOnFinish", state.enforceCleanTreeOnFinish)
            }
            for ((k, v) in extraKeys.entrySet()) {
                if (k !in KNOWN_KEYS) root.add(k, v)
            }
            Files.writeString(file, gson.toJson(root) + "\n")
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
        } catch (e: Throwable) {
            log.warn("Failed to write $file", e)
        }
    }

    private val KNOWN_KEYS = setOf(
        "startupCommand",
        "finishCommand",
        "openTerminalOnStart",
        "worktreeRoot",
        "defaultMergeStrategy",
        "branchPrefix",
        "enforceCleanTreeOnFinish",
    )

    private fun JsonObject.stringOr(key: String, default: String): String {
        val el = get(key) as? JsonPrimitive ?: return default
        return if (el.isString) el.asString else default
    }

    private fun JsonObject.boolOr(key: String, default: Boolean): Boolean {
        val el = get(key) as? JsonPrimitive ?: return default
        return if (el.isBoolean) el.asBoolean else default
    }
}
