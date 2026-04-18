package io.devdepot.conductor.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

data class GitResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

data class WorktreeEntry(val path: Path, val branch: String?, val head: String?)

object Git {
    private val log = Logger.getInstance(Git::class.java)
    private const val TIMEOUT_MS = 30_000

    private val ADJECTIVES = listOf(
        "bold", "zen", "quiet", "bright", "clever", "swift", "rustic", "bold",
        "silent", "lucid", "shy", "nimble", "plucky", "sunny", "bold", "mellow",
        "eager", "brave", "calm", "witty",
    )
    private val NOUNS = listOf(
        "fox", "owl", "otter", "lark", "wren", "moth", "reef", "pine",
        "dune", "fern", "oak", "crow", "heron", "kite", "sparrow", "badger",
        "falcon", "mole", "finch", "marten",
    )

    fun exec(args: List<String>, cwd: Path): GitResult {
        val cmd = GeneralCommandLine(listOf("git") + args)
            .withWorkDirectory(cwd.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(Charsets.UTF_8)
        val handler = CapturingProcessHandler(cmd)
        val out = handler.runProcess(TIMEOUT_MS)
        if (out.isTimeout) {
            log.warn("git ${args.joinToString(" ")} timed out in $cwd")
            return GitResult(-1, "", "git command timed out after ${TIMEOUT_MS}ms")
        }
        return GitResult(out.exitCode, out.stdout.trim(), out.stderr.trim())
    }

    fun isGitRepo(path: Path): Boolean {
        if (!Files.isDirectory(path)) return false
        return exec(listOf("rev-parse", "--git-dir"), path).ok
    }

    fun isWorktree(projectRoot: Path): Boolean {
        if (!isGitRepo(projectRoot)) return false
        val common = exec(listOf("rev-parse", "--git-common-dir"), projectRoot)
        val gitDir = exec(listOf("rev-parse", "--git-dir"), projectRoot)
        if (!common.ok || !gitDir.ok) return false
        val commonResolved = projectRoot.resolve(common.stdout).toAbsolutePath().normalize()
        val gitDirResolved = projectRoot.resolve(gitDir.stdout).toAbsolutePath().normalize()
        return commonResolved != gitDirResolved
    }

    fun mainRepoRoot(worktreePath: Path): Path? {
        val common = exec(listOf("rev-parse", "--git-common-dir"), worktreePath)
        if (!common.ok) return null
        val commonDir = worktreePath.resolve(common.stdout).toAbsolutePath().normalize()
        // common-dir points at <main>/.git; the working dir is its parent.
        return commonDir.parent
    }

    fun detectDefaultBranch(mainRepo: Path): String {
        val origin = exec(listOf("symbolic-ref", "--short", "refs/remotes/origin/HEAD"), mainRepo)
        if (origin.ok && origin.stdout.isNotBlank()) {
            return origin.stdout.removePrefix("origin/")
        }
        val current = exec(listOf("rev-parse", "--abbrev-ref", "HEAD"), mainRepo)
        if (current.ok && current.stdout.isNotBlank() && current.stdout != "HEAD") {
            return current.stdout
        }
        return "main"
    }

    fun listLocalBranches(mainRepo: Path): List<String> {
        val r = exec(listOf("for-each-ref", "--format=%(refname:short)", "refs/heads"), mainRepo)
        if (!r.ok) return emptyList()
        return r.stdout.lines().filter { it.isNotBlank() }
    }

    fun listWorktrees(mainRepo: Path): List<WorktreeEntry> {
        val r = exec(listOf("worktree", "list", "--porcelain"), mainRepo)
        if (!r.ok) return emptyList()
        return parseWorktreePorcelain(r.stdout)
    }

    fun parseWorktreePorcelain(porcelain: String): List<WorktreeEntry> {
        val entries = mutableListOf<WorktreeEntry>()
        var path: Path? = null
        var branch: String? = null
        var head: String? = null
        fun flush() {
            val p = path ?: return
            entries += WorktreeEntry(p, branch, head)
            path = null; branch = null; head = null
        }
        for (raw in porcelain.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) { flush(); continue }
            val (key, value) = line.split(' ', limit = 2).let {
                if (it.size == 2) it[0] to it[1] else it[0] to ""
            }
            when (key) {
                "worktree" -> {
                    flush()
                    path = Path.of(value)
                }
                "HEAD" -> head = value
                "branch" -> branch = value.removePrefix("refs/heads/")
                "detached" -> branch = null
            }
        }
        flush()
        return entries
    }

    fun statusPorcelain(worktree: Path): GitResult =
        exec(listOf("status", "--porcelain"), worktree)

    fun isDirty(worktree: Path): Boolean {
        val r = statusPorcelain(worktree)
        return r.ok && r.stdout.isNotBlank()
    }

    fun push(
        worktree: Path,
        remote: String = "origin",
        branch: String,
        setUpstream: Boolean = true,
    ): GitResult {
        val args = mutableListOf("push")
        if (setUpstream) args += "--set-upstream"
        args += remote
        args += branch
        return exec(args, worktree)
    }

    fun worktreeAdd(mainRepo: Path, branch: String, worktreePath: Path, base: String): GitResult =
        exec(
            listOf("worktree", "add", "-b", branch, worktreePath.toString(), base),
            mainRepo,
        )

    fun worktreeRemove(mainRepo: Path, worktreePath: Path, force: Boolean = false): GitResult {
        val args = mutableListOf("worktree", "remove")
        if (force) args += "--force"
        args += worktreePath.toString()
        return exec(args, mainRepo)
    }

    fun branchDelete(mainRepo: Path, branch: String, force: Boolean = false): GitResult =
        exec(listOf("branch", if (force) "-D" else "-d", branch), mainRepo)

    fun checkout(mainRepo: Path, ref: String): GitResult =
        exec(listOf("checkout", ref), mainRepo)

    fun mergeFfOnly(mainRepo: Path, branch: String): GitResult =
        exec(listOf("merge", "--ff-only", branch), mainRepo)

    fun mergeNoFf(mainRepo: Path, branch: String): GitResult =
        exec(listOf("merge", "--no-ff", branch), mainRepo)

    fun mergeSquash(mainRepo: Path, branch: String): GitResult =
        exec(listOf("merge", "--squash", branch), mainRepo)

    fun commit(mainRepo: Path, message: String): GitResult =
        exec(listOf("commit", "-m", message), mainRepo)

    fun mergeAbort(mainRepo: Path): GitResult =
        exec(listOf("merge", "--abort"), mainRepo)

    fun rebaseOnto(worktree: Path, base: String): GitResult =
        exec(listOf("rebase", base), worktree)

    fun rebaseAbort(worktree: Path): GitResult =
        exec(listOf("rebase", "--abort"), worktree)

    fun resetHard(worktree: Path): GitResult =
        exec(listOf("reset", "--hard"), worktree)

    fun generateBranchName(prefix: String = "wt/"): String {
        val a = ADJECTIVES.random()
        val n = NOUNS.random()
        val h = Random.nextInt(0, 0x10000).toString(16).padStart(4, '0')
        return "$prefix$a-$n-$h"
    }
}
