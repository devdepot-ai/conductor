package io.devdepot.conductor.forge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.file.Path

/**
 * PR client backed by a `bb` CLI. The exact command surface of third-party
 * Bitbucket CLIs varies; we use a minimal subset (`pr create`, `pr view`) and
 * keep the argument templates in one place so swapping CLIs is a local edit.
 *
 * The `bb` binary is expected to be already authenticated against Bitbucket.
 */
class BitbucketPrClient(
    private val binary: String,
    private val runner: (binary: String, args: List<String>, cwd: Path) -> CliResult = { b, a, c ->
        Cli.run(b, a, c)
    },
) : PrClient {

    override val forge: Forge = Forge.BITBUCKET

    override fun create(
        cwd: Path,
        head: String,
        base: String,
        title: String,
        body: String,
    ): PrOutcome<PrRef> {
        val args = listOf(
            "pr", "create",
            "--source", head,
            "--destination", base,
            "--title", title,
            "--description", body,
        )
        val r = runner(binary, args, cwd)
        if (!r.ok) return PrOutcome.Err(r.stderr.ifBlank { r.stdout }.ifBlank { "bb pr create failed (exit ${r.exitCode})." })
        val url = extractFirstUrl(r.stdout) ?: return PrOutcome.Err(
            "bb pr create succeeded but no PR URL was found in output:\n${r.stdout}",
        )
        val number = extractTrailingNumber(url) ?: return PrOutcome.Err(
            "bb pr create returned an unexpected URL: $url",
        )
        return PrOutcome.Ok(PrRef(number = number, url = url))
    }

    override fun fetchState(cwd: Path, number: Int): PrOutcome<PrRemoteState> {
        val args = listOf("pr", "view", number.toString(), "--json")
        val r = runner(binary, args, cwd)
        if (!r.ok) return PrOutcome.Err(r.stderr.ifBlank { r.stdout }.ifBlank { "bb pr view failed (exit ${r.exitCode})." })
        return try {
            val obj = JsonParser.parseString(r.stdout) as? JsonObject
                ?: return PrOutcome.Err("bb pr view returned non-object JSON:\n${r.stdout}")
            val rawState = (obj.get("state") as? JsonPrimitive)?.asString
                ?: (obj.get("status") as? JsonPrimitive)?.asString
                ?: "open"
            val normalizedState = when (rawState.lowercase()) {
                "merged", "fulfilled" -> "merged"
                "declined", "closed", "superseded" -> "closed"
                else -> "open"
            }
            val mergedAt = (obj.get("mergedAt") as? JsonPrimitive)?.let { if (it.isString) it.asString else null }
                ?: (obj.get("merged_at") as? JsonPrimitive)?.let { if (it.isString) it.asString else null }
            val url = (obj.get("url") as? JsonPrimitive)?.let { if (it.isString) it.asString else null }
                ?: (obj.get("links") as? JsonObject)
                    ?.get("html")?.asJsonObject
                    ?.get("href")?.asString
            PrOutcome.Ok(PrRemoteState(state = normalizedState, mergedAt = mergedAt, url = url))
        } catch (e: Throwable) {
            PrOutcome.Err("Failed to parse bb pr view output: ${e.message}")
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
