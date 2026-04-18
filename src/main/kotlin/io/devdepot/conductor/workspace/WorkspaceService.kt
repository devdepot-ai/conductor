package io.devdepot.conductor.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import io.devdepot.conductor.git.Git
import io.devdepot.conductor.git.GitResult
import io.devdepot.conductor.ide.ProjectOpener
import io.devdepot.conductor.settings.ConductorSettings
import io.devdepot.conductor.settings.MergeStrategy
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class WorkspaceService(private val project: Project) {

    private val log = Logger.getInstance(WorkspaceService::class.java)

    sealed class Result {
        data class Ok(val workspace: Workspace) : Result()
        data class Error(val message: String) : Result()
    }

    sealed class FinishResult {
        data class Ok(val message: String) : FinishResult()
        data class Dirty(val files: List<String>) : FinishResult()
        data class Conflict(val message: String) : FinishResult()
        data class Error(val message: String) : FinishResult()
    }

    fun projectPath(): Path? = project.basePath?.let { Path.of(it) }

    fun trunk(): Path? {
        val p = projectPath() ?: return null
        if (!Git.isGitRepo(p)) return null
        return if (Git.isWorktree(p)) Git.mainRepoRoot(p) else p
    }

    /**
     * All Conductor workspaces for the current repo. Excludes the trunk itself.
     */
    fun list(): List<Workspace> {
        val repo = trunk() ?: return emptyList()
        val entries = Git.listWorktrees(repo)
        if (entries.isEmpty()) return emptyList()
        val mainPath = repo.toAbsolutePath().normalize()
        val currentProjectPath = projectPath()?.toAbsolutePath()?.normalize()
        return entries.mapNotNull { e ->
            val ePath = e.path.toAbsolutePath().normalize()
            if (ePath == mainPath) return@mapNotNull null
            if (!ConductorMarker.isWorkspace(ePath)) return@mapNotNull null
            val branch = e.branch ?: "(detached)"
            val name = ePath.fileName?.toString() ?: branch
            Workspace(
                name = name,
                branch = branch,
                path = ePath,
                isCurrent = currentProjectPath != null && currentProjectPath == ePath,
            )
        }
    }

    fun current(): Workspace? {
        val p = projectPath()?.toAbsolutePath()?.normalize() ?: return null
        if (!ConductorMarker.isWorkspace(p)) return null
        return list().firstOrNull { it.path == p }
    }

    fun defaultWorktreeRoot(): Path {
        val repo = trunk() ?: projectPath() ?: Path.of(System.getProperty("user.home"))
        val parent = repo.parent ?: repo
        val repoName = repo.fileName?.toString() ?: "repo"
        return parent.resolve("$repoName-worktrees")
    }

    fun resolveWorktreeRoot(): Path {
        val configured = ConductorSettings.get(project).worktreeRoot.trim()
        return if (configured.isEmpty()) defaultWorktreeRoot() else Path.of(configured)
    }

    /**
     * Creates the worktree, writes the workspace marker, and opens a new IDE
     * window on it. The new window's startup activity handles the terminal tab.
     */
    fun create(branchName: String, baseBranch: String, slug: String): Result {
        val repo = trunk() ?: return Result.Error("Not inside a git repository.")
        val settings = ConductorSettings.get(project)
        val root = resolveWorktreeRoot()
        val worktreePath = root.resolve(slug).toAbsolutePath().normalize()

        if (Files.exists(worktreePath)) {
            return Result.Error("Worktree directory already exists: $worktreePath")
        }
        try {
            Files.createDirectories(root)
        } catch (e: Exception) {
            return Result.Error("Failed to create worktree root $root: ${e.message}")
        }

        val r = Git.worktreeAdd(repo, branchName, worktreePath, baseBranch)
        if (!r.ok) {
            return Result.Error("git worktree add failed:\n${r.stderr.ifBlank { r.stdout }}")
        }

        // Marker file doubles as config snapshot; written before opening the
        // window so the startup activity sees it on first open.
        try {
            ConductorMarker.writeConfig(
                worktreePath,
                ConductorMarker.Config(
                    startupCommand = settings.startupCommand,
                    openTerminalOnStart = settings.openTerminalOnStart,
                    defaultMergeStrategy = settings.defaultMergeStrategy.id,
                ),
            )
        } catch (e: Exception) {
            log.warn("Failed to write ${ConductorMarker.MARKER_FILE} in $worktreePath", e)
        }

        try {
            ensureMarkerExcluded(repo)
        } catch (e: Exception) {
            log.warn("Failed to update .git/info/exclude in $repo", e)
        }

        ApplicationManager.getApplication().invokeLater {
            project.service<ProjectOpener>().openInNewWindow(worktreePath)
        }

        val name = worktreePath.fileName?.toString() ?: slug
        val workspace = Workspace(name, branchName, worktreePath, isCurrent = false)
        return Result.Ok(workspace)
    }

    /**
     * Runs the merge/rebase/squash against the main repo, closes the worktree window,
     * removes the worktree, and optionally deletes the branch. Discard path uses --force.
     */
    fun finish(
        workspace: Workspace,
        strategy: MergeStrategy,
        baseBranch: String,
        deleteBranch: Boolean,
    ): FinishResult {
        val repo = Git.mainRepoRoot(workspace.path)
            ?: return FinishResult.Error("Could not locate main repository for ${workspace.path}.")

        if (Git.isDirty(workspace.path)) {
            val files = Git.statusPorcelain(workspace.path).stdout.lines().filter { it.isNotBlank() }
            return FinishResult.Dirty(files)
        }

        val branch = workspace.branch
        val mergeResult: GitResult = when (strategy) {
            MergeStrategy.MERGE_FF_ONLY -> {
                val co = Git.checkout(repo, baseBranch)
                if (!co.ok) return FinishResult.Error("checkout $baseBranch failed:\n${co.stderr}")
                Git.mergeFfOnly(repo, branch)
            }
            MergeStrategy.MERGE_NO_FF -> {
                val co = Git.checkout(repo, baseBranch)
                if (!co.ok) return FinishResult.Error("checkout $baseBranch failed:\n${co.stderr}")
                Git.mergeNoFf(repo, branch)
            }
            MergeStrategy.SQUASH -> {
                val co = Git.checkout(repo, baseBranch)
                if (!co.ok) return FinishResult.Error("checkout $baseBranch failed:\n${co.stderr}")
                val sq = Git.mergeSquash(repo, branch)
                if (!sq.ok) sq
                else Git.commit(repo, "Squash merge $branch")
            }
            MergeStrategy.REBASE -> {
                val rb = Git.rebaseOnto(workspace.path, baseBranch)
                if (!rb.ok) {
                    Git.rebaseAbort(workspace.path)
                    return FinishResult.Conflict(
                        "Rebase conflict — resolve manually in the worktree. Worktree preserved."
                    )
                }
                val co = Git.checkout(repo, baseBranch)
                if (!co.ok) return FinishResult.Error("checkout $baseBranch failed:\n${co.stderr}")
                Git.mergeFfOnly(repo, branch)
            }
        }

        if (!mergeResult.ok) {
            if (strategy != MergeStrategy.REBASE) {
                Git.mergeAbort(repo)
                return FinishResult.Conflict(
                    "Merge conflict — resolve manually in main repo. Worktree preserved."
                )
            }
            return FinishResult.Error("Operation failed:\n${mergeResult.stderr.ifBlank { mergeResult.stdout }}")
        }

        closeWorktreeWindow(workspace.path)

        val remove = Git.worktreeRemove(repo, workspace.path, force = false)
        if (!remove.ok) {
            return FinishResult.Error(
                "git worktree remove refused:\n${remove.stderr.ifBlank { remove.stdout }}\n\n" +
                    "Retry with Discard to force-remove."
            )
        }
        if (deleteBranch) {
            val del = Git.branchDelete(repo, branch, force = false)
            if (!del.ok) {
                log.warn("branch delete failed: ${del.stderr}")
            }
        }

        refreshMainRepoVcs(repo)
        return FinishResult.Ok("Finished `$branch` onto `$baseBranch` (${strategy.label}).")
    }

    fun discard(workspace: Workspace): FinishResult {
        val repo = Git.mainRepoRoot(workspace.path)
            ?: return FinishResult.Error("Could not locate main repository.")
        closeWorktreeWindow(workspace.path)
        val remove = Git.worktreeRemove(repo, workspace.path, force = true)
        if (!remove.ok) {
            return FinishResult.Error(
                "git worktree remove --force failed:\n${remove.stderr.ifBlank { remove.stdout }}"
            )
        }
        Git.branchDelete(repo, workspace.branch, force = true)
        refreshMainRepoVcs(repo)
        return FinishResult.Ok("Discarded `${workspace.branch}`.")
    }

    /**
     * Appends `.conductor-workspace.json` to the trunk's `.git/info/exclude`
     * once. `info/exclude` lives at the git common dir, which is shared by
     * trunk and all its worktrees — a single entry covers every workspace.
     */
    private fun ensureMarkerExcluded(trunkPath: Path) {
        val exclude = trunkPath.resolve(".git").resolve("info").resolve("exclude")
        if (!Files.isDirectory(exclude.parent)) return
        val marker = ConductorMarker.MARKER_FILE
        val existing = if (Files.isRegularFile(exclude)) Files.readString(exclude) else ""
        val hasEntry = existing.lineSequence().any { it.trim() == marker }
        if (hasEntry) return
        val needsNewline = existing.isNotEmpty() && !existing.endsWith("\n")
        val addition = buildString {
            if (needsNewline) append('\n')
            append("# added by Conductor\n")
            append(marker).append('\n')
        }
        Files.writeString(
            exclude,
            existing + addition,
        )
    }

    private fun closeWorktreeWindow(worktreePath: Path) {
        val normalized = worktreePath.toAbsolutePath().normalize().toString()
        val targets = ProjectManager.getInstance().openProjects.filter {
            it.basePath?.let { p -> Path.of(p).toAbsolutePath().normalize().toString() } == normalized
        }
        ApplicationManager.getApplication().invokeAndWait {
            targets.forEach { ProjectManager.getInstance().closeAndDispose(it) }
        }
    }

    private fun refreshMainRepoVcs(repo: Path) {
        val normalized = repo.toAbsolutePath().normalize().toString()
        val mains = ProjectManager.getInstance().openProjects.filter {
            it.basePath?.let { p -> Path.of(p).toAbsolutePath().normalize().toString() } == normalized
        }
        mains.forEach { p ->
            try {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(repo.toString())
                    ?.refresh(true, true)
                VcsDirtyScopeManager.getInstance(p).markEverythingDirty()
            } catch (e: Throwable) {
                log.warn("VCS refresh failed for $repo", e)
            }
        }
    }

    companion object {
        fun get(project: Project): WorkspaceService = project.service()
    }
}
