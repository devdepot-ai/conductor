package io.devdepot.conductor.forge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.file.Path

/**
 * PR client backed by the GitHub `gh` CLI. Assumes the user has already run
 * `gh auth login` — we don't try to bootstrap credentials from inside the IDE.
 */
class GitHubPrClient(
    private val binary: String,
    private val runner: (binary: String, args: List<String>, cwd: Path) -> CliResult = { b, a, c ->
        Cli.run(b, a, c)
    },
) : PrClient {

    override val forge: Forge = Forge.GITHUB

    override fun create(
        cwd: Path,
        head: String,
        base: String,
        title: String,
        body: String,
    ): PrOutcome<PrRef> {
        val args = listOf(
            "pr", "create",
            "--base", base,
            "--head", head,
            "--title", title,
            "--body", body,
        )
        val r = runner(binary, args, cwd)
        if (!r.ok) return PrOutcome.Err(r.stderr.ifBlank { r.stdout }.ifBlank { "gh pr create failed (exit ${r.exitCode})." })
        val url = extractFirstUrl(r.stdout) ?: return PrOutcome.Err(
            "gh pr create succeeded but no PR URL was found in output:\n${r.stdout}",
        )
        val number = extractTrailingNumber(url) ?: return PrOutcome.Err(
            "gh pr create returned an unexpected URL: $url",
        )
        return PrOutcome.Ok(PrRef(number = number, url = url))
    }

    override fun fetchState(cwd: Path, number: Int): PrOutcome<PrRemoteState> {
        val args = listOf("pr", "view", number.toString(), "--json", "state,mergedAt,url")
        val r = runner(binary, args, cwd)
        if (!r.ok) return PrOutcome.Err(r.stderr.ifBlank { r.stdout }.ifBlank { "gh pr view failed (exit ${r.exitCode})." })
        return try {
            val obj = JsonParser.parseString(r.stdout) as? JsonObject
                ?: return PrOutcome.Err("gh pr view returned non-object JSON:\n${r.stdout}")
            val state = (obj.get("state") as? JsonPrimitive)?.asString ?: "open"
            val mergedAt = (obj.get("mergedAt") as? JsonPrimitive)?.let { if (it.isString) it.asString else null }
            val url = (obj.get("url") as? JsonPrimitive)?.let { if (it.isString) it.asString else null }
            PrOutcome.Ok(PrRemoteState(state = state.lowercase(), mergedAt = mergedAt, url = url))
        } catch (e: Throwable) {
            PrOutcome.Err("Failed to parse gh pr view output: ${e.message}")
        }
    }

    private fun extractFirstUrl(stdout: String): String? =
        stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }

    private fun extractTrailingNumber(url: String): Int? {
        val tail = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return tail.toIntOrNull()
    }
}
