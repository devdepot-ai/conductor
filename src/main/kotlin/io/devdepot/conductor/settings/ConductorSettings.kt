package io.devdepot.conductor.settings

import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path

enum class MergeStrategy(val id: String, val label: String) {
    MERGE_FF_ONLY("merge-ff-only", "merge (ff-only)"),
    MERGE_NO_FF("merge-no-ff", "merge (--no-ff)"),
    REBASE("rebase", "rebase"),
    SQUASH("squash", "squash");

    companion object {
        fun fromId(id: String?): MergeStrategy =
            values().firstOrNull { it.id == id } ?: MERGE_NO_FF
    }
}

@Service(Service.Level.PROJECT)
class ConductorSettings(private val project: Project) {

    data class State(
        var startupCommand: String = "",
        var finishCommand: String = "",
        var openTerminalOnStart: Boolean = false,
        var worktreeRoot: String = "",
        var defaultMergeStrategy: String = MergeStrategy.MERGE_NO_FF.id,
        var branchPrefix: String = "wt/",
        var enforceCleanTreeOnFinish: Boolean = true,
        var localFinishEnabled: Boolean = false,
        var createPrOnFinish: Boolean = true,
        var autoReapOnMerge: Boolean = false,
        var prPollIntervalSeconds: Int = 120,
        var ghCliCommand: String = "gh",
        var bbCliCommand: String = "bb",
    )

    private var state: State = State()
    private var extraKeys: JsonObject = JsonObject()
    private var loaded: Boolean = false

    private fun ensureLoaded() {
        if (loaded) return
        val root = repoRoot()
        if (root != null) {
            val result = ConductorSettingsFile.read(root)
            if (result != null) {
                state = result.state
                extraKeys = result.extraKeys
            }
        }
        loaded = true
    }

    fun reload() {
        loaded = false
        ensureLoaded()
    }

    fun save() {
        val root = repoRoot() ?: return
        ConductorSettingsFile.write(root, state, extraKeys)
    }

    private fun repoRoot(): Path? = project.basePath?.let(Path::of)

    var startupCommand: String
        get() { ensureLoaded(); return state.startupCommand }
        set(v) { ensureLoaded(); state.startupCommand = v }

    var finishCommand: String
        get() { ensureLoaded(); return state.finishCommand }
        set(v) { ensureLoaded(); state.finishCommand = v }

    var openTerminalOnStart: Boolean
        get() { ensureLoaded(); return state.openTerminalOnStart }
        set(v) { ensureLoaded(); state.openTerminalOnStart = v }

    var worktreeRoot: String
        get() { ensureLoaded(); return state.worktreeRoot }
        set(v) { ensureLoaded(); state.worktreeRoot = v }

    var defaultMergeStrategy: MergeStrategy
        get() { ensureLoaded(); return MergeStrategy.fromId(state.defaultMergeStrategy) }
        set(v) { ensureLoaded(); state.defaultMergeStrategy = v.id }

    var branchPrefix: String
        get() { ensureLoaded(); return state.branchPrefix.ifBlank { "wt/" } }
        set(v) { ensureLoaded(); state.branchPrefix = v }

    val enforceCleanTreeOnFinish: Boolean
        get() { ensureLoaded(); return state.enforceCleanTreeOnFinish }

    var localFinishEnabled: Boolean
        get() { ensureLoaded(); return state.localFinishEnabled }
        set(v) { ensureLoaded(); state.localFinishEnabled = v }

    var createPrOnFinish: Boolean
        get() { ensureLoaded(); return state.createPrOnFinish }
        set(v) { ensureLoaded(); state.createPrOnFinish = v }

    var autoReapOnMerge: Boolean
        get() { ensureLoaded(); return state.autoReapOnMerge }
        set(v) { ensureLoaded(); state.autoReapOnMerge = v }

    var prPollIntervalSeconds: Int
        get() { ensureLoaded(); return state.prPollIntervalSeconds.coerceAtLeast(30) }
        set(v) { ensureLoaded(); state.prPollIntervalSeconds = v.coerceAtLeast(30) }

    var ghCliCommand: String
        get() { ensureLoaded(); return state.ghCliCommand.ifBlank { "gh" } }
        set(v) { ensureLoaded(); state.ghCliCommand = v }

    var bbCliCommand: String
        get() { ensureLoaded(); return state.bbCliCommand.ifBlank { "bb" } }
        set(v) { ensureLoaded(); state.bbCliCommand = v }

    companion object {
        fun get(project: Project): ConductorSettings = project.service()
    }
}
