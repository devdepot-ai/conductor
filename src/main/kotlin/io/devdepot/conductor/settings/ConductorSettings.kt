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

    companion object {
        fun get(project: Project): ConductorSettings = project.service()
    }
}
