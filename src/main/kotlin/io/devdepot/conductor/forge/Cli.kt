package io.devdepot.conductor.forge

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path

data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Generic subprocess runner for forge CLIs (`gh`, `bb`, future tools).
 * Mirrors [io.devdepot.conductor.git.Git.exec] but lets callers pick the
 * binary so users can point at wrappers or absolute paths.
 */
object Cli {
    private val log = Logger.getInstance(Cli::class.java)
    private const val DEFAULT_TIMEOUT_MS = 60_000

    fun run(
        binary: String,
        args: List<String>,
        cwd: Path,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): CliResult {
        val cmd = GeneralCommandLine(listOf(binary) + args)
            .withWorkDirectory(cwd.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(Charsets.UTF_8)
        return try {
            val handler = CapturingProcessHandler(cmd)
            val out = handler.runProcess(timeoutMs)
            if (out.isTimeout) {
                log.warn("$binary ${args.joinToString(" ")} timed out in $cwd")
                return CliResult(-1, "", "$binary timed out after ${timeoutMs}ms")
            }
            CliResult(out.exitCode, out.stdout.trim(), out.stderr.trim())
        } catch (e: Throwable) {
            log.warn("$binary invocation failed: ${e.message}")
            CliResult(-1, "", e.message ?: "process failed")
        }
    }
}
