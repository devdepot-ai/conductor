package io.devdepot.conductor.claude

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Application-level detector for Claude Code presence and hook installation
 * state. Result is cached; call [invalidate] after install/uninstall.
 */
@Service(Service.Level.APP)
class ClaudeCodeDetector {

    enum class State {
        /** `claude` CLI not found and ~/.claude not present. UI hides Claude features. */
        NotInstalled,
        /** Claude is installed but our hooks are not. UI prompts to install. */
        InstalledNoHooks,
        /** Hooks installed at a version older than [ClaudeHookInstaller.SCRIPT_VERSION]. */
        InstalledStaleHooks,
        /** All five lifecycle hooks installed at the current version. */
        InstalledWithHooks,
    }

    private val cached = AtomicReference<State?>(null)

    fun get(): State = cached.get() ?: detect().also { cached.set(it) }

    fun invalidate() { cached.set(null) }

    private fun detect(): State {
        if (!claudeInstalled()) return State.NotInstalled
        val installed = ClaudeHookInstaller.installedEvents()
        val allPresent = ClaudeHookInstaller.HOOKED_EVENTS.all { it in installed }
        if (!allPresent) return State.InstalledNoHooks
        val version = ClaudeHookInstaller.installedVersion()
        return if (version == ClaudeHookInstaller.SCRIPT_VERSION) {
            State.InstalledWithHooks
        } else {
            State.InstalledStaleHooks
        }
    }

    private fun claudeInstalled(): Boolean {
        // Either signal is sufficient: CLI on PATH OR ~/.claude exists from a
        // previous install (covers cases where the IDE's trimmed PATH misses
        // a CLI install that the user-shell can see).
        if (Files.isDirectory(ClaudeHookInstaller.home.resolve(".claude"))) return true
        return findClaudeOnPath() != null
    }

    private fun findClaudeOnPath(): Path? {
        val pathEnv = System.getenv("PATH") ?: ""
        val dirs = pathEnv.split(java.io.File.pathSeparator).filter { it.isNotBlank() }
        val extras = listOf(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            System.getProperty("user.home") + "/.local/bin",
            System.getProperty("user.home") + "/.claude/local/bin",
        )
        for (d in dirs + extras) {
            val p = Path.of(d).resolve("claude")
            if (Files.isRegularFile(p) || Files.isSymbolicLink(p)) return p
        }
        return null
    }

    companion object {
        fun get(): ClaudeCodeDetector =
            com.intellij.openapi.application.ApplicationManager.getApplication().service()
    }
}
