package io.devdepot.conductor.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GitHubPrClientTest {

    private fun client(runner: (String, List<String>, Path) -> CliResult) =
        GitHubPrClient(binary = "gh", runner = runner)

    @Test
    fun `create parses url and number`() {
        val c = client { _, _, _ ->
            CliResult(exitCode = 0, stdout = "https://github.com/org/repo/pull/42", stderr = "")
        }
        val r = c.create(Path.of("/tmp"), head = "feature", base = "main", title = "t", body = "b")
        assertTrue(r is PrOutcome.Ok, "expected Ok, got $r")
        val ref = (r as PrOutcome.Ok).value
        assertEquals(42, ref.number)
        assertEquals("https://github.com/org/repo/pull/42", ref.url)
    }

    @Test
    fun `create surfaces non-zero exit`() {
        val c = client { _, _, _ ->
            CliResult(exitCode = 1, stdout = "", stderr = "authentication required")
        }
        val r = c.create(Path.of("/tmp"), head = "feature", base = "main", title = "t", body = "b")
        assertTrue(r is PrOutcome.Err)
        assertTrue((r as PrOutcome.Err).message.contains("authentication"))
    }

    @Test
    fun `create rejects output without url`() {
        val c = client { _, _, _ ->
            CliResult(exitCode = 0, stdout = "OK but no url here", stderr = "")
        }
        val r = c.create(Path.of("/tmp"), head = "feature", base = "main", title = "t", body = "b")
        assertTrue(r is PrOutcome.Err)
    }

    @Test
    fun `fetchState returns merged`() {
        val c = client { _, _, _ ->
            CliResult(
                exitCode = 0,
                stdout = """{"state":"MERGED","mergedAt":"2026-04-18T10:00:00Z","url":"u"}""",
                stderr = "",
            )
        }
        val r = c.fetchState(Path.of("/tmp"), number = 42)
        assertTrue(r is PrOutcome.Ok)
        val state = (r as PrOutcome.Ok).value
        assertEquals("merged", state.state)
        assertEquals("2026-04-18T10:00:00Z", state.mergedAt)
    }

    @Test
    fun `fetchState normalises case`() {
        val c = client { _, _, _ ->
            CliResult(
                exitCode = 0,
                stdout = """{"state":"OPEN","mergedAt":null,"url":"u"}""",
                stderr = "",
            )
        }
        val r = c.fetchState(Path.of("/tmp"), number = 1)
        assertTrue(r is PrOutcome.Ok)
        assertEquals("open", (r as PrOutcome.Ok).value.state)
    }
}
