package io.devdepot.conductor.claude

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Installs/uninstalls the Conductor hook into Claude Code's user-global
 * settings file.
 *
 * Single source of truth: the hook script lives at
 * ~/.conductor/bin/conductor-claude-hook. Each of the five lifecycle events
 * in ~/.claude/settings.json gets one entry whose command points at that
 * path. Detection is by exact path match — the path itself is the sentinel.
 *
 * SCRIPT_VERSION is bumped only when the script's contract with the plugin
 * (state file format, supported events) changes. Detector treats a mismatch
 * as "stale hooks" so the user is prompted to reinstall.
 */
object ClaudeHookInstaller {

    const val SCRIPT_VERSION = "3"
    private const val HOOK_RESOURCE = "/scripts/conductor-claude-hook"
    private val log = Logger.getInstance(ClaudeHookInstaller::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Lifecycle events we install hooks for. PreToolUse/PostToolUse give us
     * a "back to working" signal after the user approves a permission
     * prompt — without them, Notification would stick until Stop.
     */
    val HOOKED_EVENTS = listOf(
        "SessionStart",
        "UserPromptSubmit",
        "PreToolUse",
        "PostToolUse",
        "Stop",
        "Notification",
        "SessionEnd",
    )

    val home: Path get() = Path.of(System.getProperty("user.home"))
    val claudeSettingsFile: Path get() = home.resolve(".claude").resolve("settings.json")
    val hookScriptPath: Path get() = home.resolve(".conductor").resolve("bin").resolve("conductor-claude-hook")
    private val versionFile: Path get() = home.resolve(".conductor").resolve("bin").resolve(".version")

    sealed class Result {
        object Ok : Result()
        data class Error(val message: String) : Result()
    }

    fun install(): Result {
        try {
            writeHookScript()
            mergeSettings(install = true)
            return Result.Ok
        } catch (e: Throwable) {
            log.warn("Claude hook install failed", e)
            return Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun uninstall(): Result {
        try {
            mergeSettings(install = false)
            runCatching { Files.deleteIfExists(hookScriptPath) }
            runCatching { Files.deleteIfExists(versionFile) }
            return Result.Ok
        } catch (e: Throwable) {
            log.warn("Claude hook uninstall failed", e)
            return Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun installedVersion(): String? {
        if (!Files.isRegularFile(versionFile)) return null
        return runCatching { Files.readString(versionFile).trim() }.getOrNull()
    }

    /**
     * Reads ~/.claude/settings.json and returns the set of event names whose
     * hooks already reference our script path. Used by the detector to
     * decide between installed-with-hooks vs installed-no-hooks.
     */
    fun installedEvents(): Set<String> {
        val root = readSettings() ?: return emptySet()
        val hooks = root.getAsJsonObject("hooks") ?: return emptySet()
        val targetCommand = hookScriptPath.toString()
        val present = mutableSetOf<String>()
        for (event in HOOKED_EVENTS) {
            val arr = hooks.getAsJsonArray(event) ?: continue
            if (arr.any { entry ->
                    val inner = (entry as? JsonObject)?.getAsJsonArray("hooks") ?: return@any false
                    inner.any { hk ->
                        val cmd = (hk as? JsonObject)?.get("command")?.asString
                        cmd == targetCommand
                    }
                }) {
                present.add(event)
            }
        }
        return present
    }

    private fun writeHookScript() {
        val bytes = javaClass.getResourceAsStream(HOOK_RESOURCE)?.use { it.readBytes() }
            ?: error("Bundled hook script $HOOK_RESOURCE missing from plugin jar")
        Files.createDirectories(hookScriptPath.parent)
        Files.write(hookScriptPath, bytes)
        markExecutable(hookScriptPath)
        Files.writeString(versionFile, SCRIPT_VERSION)
    }

    private fun markExecutable(path: Path) {
        try {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms += PosixFilePermission.OWNER_EXECUTE
            perms += PosixFilePermission.GROUP_EXECUTE
            perms += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(path, perms)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (Windows). Java's File.setExecutable still works.
            path.toFile().setExecutable(true, false)
        }
    }

    private fun mergeSettings(install: Boolean) {
        val root = readSettings() ?: JsonObject()
        val hooks = root.getAsJsonObject("hooks") ?: JsonObject().also { root.add("hooks", it) }
        val targetCommand = hookScriptPath.toString()
        for (event in HOOKED_EVENTS) {
            val arr = hooks.getAsJsonArray(event) ?: JsonArray().also { hooks.add(event, it) }
            stripOurEntries(arr, targetCommand)
            if (install) arr.add(ourEntry(targetCommand))
            if (arr.size() == 0) hooks.remove(event)
        }
        if (hooks.size() == 0) root.remove("hooks")
        writeSettings(root)
    }

    private fun ourEntry(command: String): JsonObject {
        val inner = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", command)
        }
        val hooksArr = JsonArray().apply { add(inner) }
        return JsonObject().apply { add("hooks", hooksArr) }
    }

    /**
     * Remove any matcher group whose only command is ours, plus any of our
     * command entries inside larger groups. Leaves unrelated user hooks
     * intact.
     */
    private fun stripOurEntries(arr: JsonArray, targetCommand: String) {
        val keep = mutableListOf<JsonObject>()
        for (i in 0 until arr.size()) {
            val entry = arr.get(i) as? JsonObject ?: continue
            val inner = entry.getAsJsonArray("hooks")
            if (inner == null) {
                keep.add(entry)
                continue
            }
            val filtered = JsonArray()
            for (j in 0 until inner.size()) {
                val hk = inner.get(j) as? JsonObject ?: continue
                val cmd = hk.get("command")?.asString
                if (cmd != targetCommand) filtered.add(hk)
            }
            if (filtered.size() > 0) {
                entry.add("hooks", filtered)
                keep.add(entry)
            }
        }
        // Replace contents
        while (arr.size() > 0) arr.remove(0)
        keep.forEach { arr.add(it) }
    }

    private fun readSettings(): JsonObject? {
        if (!Files.isRegularFile(claudeSettingsFile)) return null
        return try {
            val text = Files.readString(claudeSettingsFile)
            if (text.isBlank()) null else JsonParser.parseString(text) as? JsonObject
        } catch (e: Throwable) {
            log.warn("Failed to parse $claudeSettingsFile", e)
            null
        }
    }

    private fun writeSettings(root: JsonObject) {
        Files.createDirectories(claudeSettingsFile.parent)
        Files.writeString(claudeSettingsFile, gson.toJson(root) + "\n")
    }
}
