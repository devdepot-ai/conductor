package io.devdepot.conductor.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BitbucketPrClientTest {

    private fun client(runner: (String, List<String>, Path) -> CliResult) =
        BitbucketPrClient(binary = "bb", runner = runner)

    @Test
    fun `create parses url and number`() {
        val c = client { _, _, _ ->
            CliResult(
                exitCode = 0,
                stdout = "https://bitbucket.org/team/repo/pull-requests/7",
                stderr = "",
            )
        }
        val r = c.create(Path.of("/tmp"), head = "feature", base = "main", title = "t", body = "b")
        assertTrue(r is PrOutcome.Ok, "expected Ok, got $r")
        val ref = (r as PrOutcome.Ok).value
        assertEquals(7, ref.number)
    }

    @Test
    fun `fetchState maps fulfilled to merged`() {
        val c = client { _, _, _ ->
            CliResult(
                exitCode = 0,
                stdout = """{"state":"FULFILLED","mergedAt":"2026-04-18T10:00:00Z"}""",
                stderr = "",
            )
        }
        val r = c.fetchState(Path.of("/tmp"), number = 1)
        assertTrue(r is PrOutcome.Ok)
        assertEquals("merged", (r as PrOutcome.Ok).value.state)
    }

    @Test
    fun `fetchState maps declined to closed`() {
        val c = client { _, _, _ ->
            CliResult(
                exitCode = 0,
                stdout = """{"state":"DECLINED"}""",
                stderr = "",
            )
        }
        val r = c.fetchState(Path.of("/tmp"), number = 1)
        assertTrue(r is PrOutcome.Ok)
        assertEquals("closed", (r as PrOutcome.Ok).value.state)
    }
}
