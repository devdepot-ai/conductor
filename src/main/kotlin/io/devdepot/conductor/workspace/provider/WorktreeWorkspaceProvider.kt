package io.devdepot.conductor.workspace.provider

import com.intellij.openapi.application.ApplicationManager
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
import io.devdepot.conductor.workspace.ConductorMarker
import io.devdepot.conductor.workspace.FinishLog
import io.devdepot.conductor.workspace.Workspace
import io.devdepot.conductor.workspace.WorkspaceService
import io.devdepot.conductor.workspace.WorktreeWorkspace
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * Git-worktree-backed implementation of [WorkspaceProvider]. Every workspace
 * is a `git worktree` with a [ConductorMarker.MARKER_FILE] at its root; the
 * marker both identifies the workspace and carries the config snapshot.
 */
class WorktreeWorkspaceProvider : WorkspaceProvider {

    private val log = Logger.getInstance(WorktreeWorkspaceProvider::class.java)

    override val id: String = "worktree"

    override fun enumerate(project: Project, trunk: Path): List<Workspace> {
        val entries = Git.listWorktrees(trunk)
        val mainPath = trunk.toAbsolutePath().normalize()
        val currentProjectPath = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        val openProjectPaths = ProjectManager.getInstance().openProjects
            .mapNotNull { it.basePath?.let { p -> Path.of(p).toAbsolutePath().normalize() } }
            .toSet()
        return entries.mapNotNull { e ->
            val ePath = e.path.toAbsolutePath().normalize()
            if (ePath == mainPath) return@mapNotNull null
            if (!ConductorMarker.isWorkspace(ePath)) return@mapNotNull null
            val branch = e.branch ?: "(detached)"
            val config = runCatching { ConductorMarker.readConfig(ePath) }.getOrNull()
            val name = config?.name?.takeIf { it.isNotBlank() }
                ?: ePath.fileName?.toString()
                ?: branch
            WorktreeWorkspace(
                name = name,
                branch = branch,
                isCurrent = currentProjectPath == ePath,
                worktreePath = ePath,
                createdAt = resolveCreatedAt(ePath, config?.createdAt),
                isOpen = ePath in openProjectPaths,
            )
        }
    }

    private fun resolveCreatedAt(workspaceRoot: Path, fromMarker: String?): Instant? {
        if (!fromMarker.isNullOrBlank()) {
            runCatching { return Instant.parse(fromMarker) }
        }
        return runCatching {
            Files.readAttributes(workspaceRoot, BasicFileAttributes::class.java)
                .creationTime()
                .toInstant()
        }.getOrNull()
    }

    override fun create(project: Project, spec: CreateSpec): WorkspaceService.Result {
        val service = WorkspaceService.get(project)
        val repo = service.trunk() ?: return WorkspaceService.Result.Error("Not inside a git repository.")
        val settings = ConductorSettings.get(project)
        val root = service.resolveWorktreeRoot()
        val worktreePath = root.resolve(spec.slug).toAbsolutePath().normalize()

        if (Files.exists(worktreePath)) {
            return WorkspaceService.Result.Error("Worktree directory already exists: $worktreePath")
        }
        try {
            Files.createDirectories(root)
        } catch (e: Exception) {
            return WorkspaceService.Result.Error("Failed to create worktree root $root: ${e.message}")
        }

        val r = Git.worktreeAdd(repo, spec.branchName, worktreePath, spec.baseBranch)
        if (!r.ok) {
            return WorkspaceService.Result.Error("git worktree add failed:\n${r.stderr.ifBlank { r.stdout }}")
        }

        val createdAt = Instant.now()
        // Marker file doubles as config snapshot; written before opening the
        // window so the startup activity sees it on first open.
        try {
            ConductorMarker.writeConfig(
                worktreePath,
                ConductorMarker.Config(
                    startupCommand = settings.startupCommand,
                    openTerminalOnStart = settings.openTerminalOnStart,
                    defaultMergeStrategy = settings.defaultMergeStrategy.id,
                    createdAt = createdAt.toString(),
                    name = spec.branchName,
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

        val workspace = WorktreeWorkspace(
            name = spec.branchName,
            branch = spec.branchName,
            isCurrent = false,
            worktreePath = worktreePath,
            createdAt = createdAt,
        )
        return WorkspaceService.Result.Ok(workspace)
    }

    override fun finish(
        project: Project,
        workspace: Workspace,
        strategy: MergeStrategy,
        baseBranch: String,
        deleteBranch: Boolean,
        log: FinishLog?,
    ): WorkspaceService.FinishResult {
        require(workspace is WorktreeWorkspace) {
            "WorktreeWorkspaceProvider can only finish WorktreeWorkspaces, got ${workspace::class.simpleName}"
        }
        val repo = Git.mainRepoRoot(workspace.worktreePath)
            ?: return WorkspaceService.FinishResult.Error(
                "Could not locate main repository for ${workspace.worktreePath}.",
            )

        if (Git.isDirty(workspace.worktreePath)) {
            val files = Git.statusPorcelain(workspace.worktreePath).stdout.lines()
                .filter { it.isNotBlank() }
            return WorkspaceService.FinishResult.Dirty(files)
        }

        val branch = workspace.branch
        val mergeResult: GitResult = when (strategy) {
            MergeStrategy.MERGE_FF_ONLY -> {
                val co = Git.checkout(repo, baseBranch)
                log?.command("git checkout $baseBranch", co)
                if (!co.ok) return WorkspaceService.FinishResult.Error("checkout $baseBranch failed:\n${co.stderr}")
                Git.mergeFfOnly(repo, branch).also { log?.command("git merge --ff-only $branch", it) }
            }
            MergeStrategy.MERGE_NO_FF -> {
                val co = Git.checkout(repo, baseBranch)
                log?.command("git checkout $baseBranch", co)
                if (!co.ok) return WorkspaceService.FinishResult.Error("checkout $baseBranch failed:\n${co.stderr}")
                Git.mergeNoFf(repo, branch).also { log?.command("git merge --no-ff $branch", it) }
            }
            MergeStrategy.SQUASH -> {
                val co = Git.checkout(repo, baseBranch)
                log?.command("git checkout $baseBranch", co)
                if (!co.ok) return WorkspaceService.FinishResult.Error("checkout $baseBranch failed:\n${co.stderr}")
                val sq = Git.mergeSquash(repo, branch)
                log?.command("git merge --squash $branch", sq)
                if (!sq.ok) sq
                else Git.commit(repo, "Squash merge $branch")
                    .also { log?.command("git commit -m 'Squash merge $branch'", it) }
            }
            MergeStrategy.REBASE -> {
                val rb = Git.rebaseOnto(workspace.worktreePath, baseBranch)
                log?.command("git rebase $baseBranch", rb)
                if (!rb.ok) {
                    val abort = Git.rebaseAbort(workspace.worktreePath)
                    log?.command("git rebase --abort", abort)
                    return WorkspaceService.FinishResult.Conflict(
                        "Rebase conflict — resolve manually in the worktree. Worktree preserved.",
                    )
                }
                val co = Git.checkout(repo, baseBranch)
                log?.command("git checkout $baseBranch", co)
                if (!co.ok) return WorkspaceService.FinishResult.Error("checkout $baseBranch failed:\n${co.stderr}")
                Git.mergeFfOnly(repo, branch).also { log?.command("git merge --ff-only $branch", it) }
            }
        }

        if (!mergeResult.ok) {
            if (strategy != MergeStrategy.REBASE) {
                val abort = Git.mergeAbort(repo)
                log?.command("git merge --abort", abort)
                return WorkspaceService.FinishResult.Conflict(
                    "Merge conflict — resolve manually in main repo. Worktree preserved.",
                )
            }
            return WorkspaceService.FinishResult.Error(
                "Operation failed:\n${mergeResult.stderr.ifBlank { mergeResult.stdout }}",
            )
        }

        val teardown = teardown(repo, workspace.worktreePath, branch, deleteBranch, force = false, log = log)
        if (teardown != null) return teardown

        return WorkspaceService.FinishResult.Ok(
            "Finished `$branch` onto `$baseBranch` (${strategy.label}).",
        )
    }

    override fun reap(
        project: Project,
        workspace: Workspace,
        deleteBranch: Boolean,
    ): WorkspaceService.FinishResult {
        require(workspace is WorktreeWorkspace) {
            "WorktreeWorkspaceProvider can only reap WorktreeWorkspaces, got ${workspace::class.simpleName}"
        }
        val repo = Git.mainRepoRoot(workspace.worktreePath)
            ?: return WorkspaceService.FinishResult.Error("Could not locate main repository.")
        val teardown = teardown(
            repo,
            workspace.worktreePath,
            workspace.branch,
            deleteBranch,
            force = false,
        )
        if (teardown != null) return teardown
        return WorkspaceService.FinishResult.Ok("Reaped `${workspace.branch}`.")
    }

    /**
     * Shared teardown body: close the IDE window, remove the worktree,
     * optionally delete the branch, refresh the trunk's VCS. Returns `null`
     * on success, or a pre-populated error result for the caller to surface.
     */
    private fun teardown(
        repo: Path,
        worktreePath: Path,
        branch: String,
        deleteBranch: Boolean,
        force: Boolean,
        log: FinishLog? = null,
    ): WorkspaceService.FinishResult? {
        closeWorktreeWindow(worktreePath)
        val remove = Git.worktreeRemove(repo, worktreePath, force = force)
        log?.command(
            "git worktree remove${if (force) " --force" else ""} $worktreePath",
            remove,
        )
        if (!remove.ok) {
            return WorkspaceService.FinishResult.Error(
                "git worktree remove${if (force) " --force" else ""} failed:\n" +
                    "${remove.stderr.ifBlank { remove.stdout }}" +
                    if (force) "" else "\n\nRetry with Discard to force-remove.",
            )
        }
        if (deleteBranch) {
            val del = Git.branchDelete(repo, branch, force = force)
            log?.command("git branch ${if (force) "-D" else "-d"} $branch", del)
            if (!del.ok) {
                this.log.warn("branch delete failed: ${del.stderr}")
            }
        }
        refreshMainRepoVcs(repo)
        return null
    }

    override fun discard(project: Project, workspace: Workspace): WorkspaceService.FinishResult {
        require(workspace is WorktreeWorkspace) {
            "WorktreeWorkspaceProvider can only discard WorktreeWorkspaces, got ${workspace::class.simpleName}"
        }
        val repo = Git.mainRepoRoot(workspace.worktreePath)
            ?: return WorkspaceService.FinishResult.Error("Could not locate main repository.")
        val teardown = teardown(
            repo,
            workspace.worktreePath,
            workspace.branch,
            deleteBranch = true,
            force = true,
        )
        if (teardown != null) return teardown
        return WorkspaceService.FinishResult.Ok("Discarded `${workspace.branch}`.")
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
        Files.writeString(exclude, existing + addition)
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
}
