package io.devdepot.conductor.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

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

@State(
    name = "ConductorSettings",
    storages = [Storage("conductor.xml")],
)
@Service(Service.Level.PROJECT)
class ConductorSettings : PersistentStateComponent<ConductorSettings.State> {

    data class State(
        var startupCommand: String = "",
        var finishCommand: String = "",
        var openTerminalOnStart: Boolean = true,
        var worktreeRoot: String = "",
        var defaultMergeStrategy: String = MergeStrategy.MERGE_NO_FF.id,
        var branchPrefix: String = "wt/",
        var enforceCleanTreeOnFinish: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    var startupCommand: String
        get() = state.startupCommand
        set(v) { state.startupCommand = v }

    var finishCommand: String
        get() = state.finishCommand
        set(v) { state.finishCommand = v }

    var openTerminalOnStart: Boolean
        get() = state.openTerminalOnStart
        set(v) { state.openTerminalOnStart = v }

    var worktreeRoot: String
        get() = state.worktreeRoot
        set(v) { state.worktreeRoot = v }

    var defaultMergeStrategy: MergeStrategy
        get() = MergeStrategy.fromId(state.defaultMergeStrategy)
        set(v) { state.defaultMergeStrategy = v.id }

    var branchPrefix: String
        get() = state.branchPrefix.ifBlank { "wt/" }
        set(v) { state.branchPrefix = v }

    val enforceCleanTreeOnFinish: Boolean
        get() = state.enforceCleanTreeOnFinish

    companion object {
        fun get(project: Project): ConductorSettings = project.service()
    }
}
