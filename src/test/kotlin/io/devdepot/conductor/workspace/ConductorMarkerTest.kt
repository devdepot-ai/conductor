package io.devdepot.conductor.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConductorMarkerTest {

    @Test
    fun `round trip without pr block`(@TempDir tmp: Path) {
        val config = ConductorMarker.Config(
            startupCommand = "claude",
            openTerminalOnStart = true,
            defaultMergeStrategy = "merge-no-ff",
            createdAt = "2026-04-18T10:00:00Z",
            name = "bold-fox",
        )
        ConductorMarker.writeConfig(tmp, config)

        val read = ConductorMarker.readConfig(tmp)
        assertNotNull(read)
        assertEquals(config.startupCommand, read!!.startupCommand)
        assertEquals(config.openTerminalOnStart, read.openTerminalOnStart)
        assertEquals(config.defaultMergeStrategy, read.defaultMergeStrategy)
        assertEquals(config.createdAt, read.createdAt)
        assertEquals(config.name, read.name)
        assertNull(read.pr)
    }

    @Test
    fun `round trip with pr block`(@TempDir tmp: Path) {
        val pr = ConductorMarker.PrState(
            forge = "github",
            number = 42,
            url = "https://github.com/org/repo/pull/42",
            baseBranch = "main",
            headBranch = "wt/bold-fox",
            state = "open",
            lastCheckedAt = "2026-04-18T10:00:00Z",
        )
        ConductorMarker.writeConfig(tmp, ConductorMarker.Config(
            startupCommand = "",
            openTerminalOnStart = false,
            defaultMergeStrategy = "",
            pr = pr,
        ))

        val read = ConductorMarker.readConfig(tmp)
        assertNotNull(read?.pr)
        val roundtrip = read!!.pr!!
        assertEquals(pr.forge, roundtrip.forge)
        assertEquals(pr.number, roundtrip.number)
        assertEquals(pr.url, roundtrip.url)
        assertEquals(pr.baseBranch, roundtrip.baseBranch)
        assertEquals(pr.headBranch, roundtrip.headBranch)
        assertEquals(pr.state, roundtrip.state)
        assertEquals(pr.lastCheckedAt, roundtrip.lastCheckedAt)
        assertNull(roundtrip.mergedAt)
    }

    @Test
    fun `writePrState preserves existing config keys`(@TempDir tmp: Path) {
        ConductorMarker.writeConfig(tmp, ConductorMarker.Config(
            startupCommand = "claude",
            openTerminalOnStart = true,
            defaultMergeStrategy = "rebase",
            name = "swift-owl",
        ))
        val pr = ConductorMarker.PrState(
            forge = "bitbucket",
            number = 7,
            url = "https://bitbucket.org/team/repo/pull-requests/7",
            baseBranch = "main",
            headBranch = "wt/swift-owl",
            state = "open",
            lastCheckedAt = "2026-04-18T10:00:00Z",
        )
        ConductorMarker.writePrState(tmp, pr)

        val read = ConductorMarker.readConfig(tmp)
        assertNotNull(read)
        assertEquals("claude", read!!.startupCommand)
        assertEquals(true, read.openTerminalOnStart)
        assertEquals("rebase", read.defaultMergeStrategy)
        assertEquals("swift-owl", read.name)
        assertEquals(pr.number, read.pr?.number)
    }

    @Test
    fun `clearPrState removes pr block`(@TempDir tmp: Path) {
        ConductorMarker.writeConfig(tmp, ConductorMarker.Config(
            startupCommand = "",
            openTerminalOnStart = false,
            defaultMergeStrategy = "",
            pr = ConductorMarker.PrState(
                forge = "github",
                number = 1,
                url = "https://example.com/1",
                baseBranch = "main",
                headBranch = "feature",
                state = "open",
                lastCheckedAt = "2026-04-18T10:00:00Z",
            ),
        ))
        ConductorMarker.clearPrState(tmp)

        val read = ConductorMarker.readConfig(tmp)
        assertNotNull(read)
        assertNull(read!!.pr)
    }

    @Test
    fun `reads legacy hand written marker`(@TempDir tmp: Path) {
        val legacy = """
            {
              "startupCommand": "claude",
              "openTerminalOnStart": true,
              "defaultMergeStrategy": "squash",
              "createdAt": "2025-12-01T09:00:00Z",
              "name": "legacy-ws"
            }
        """.trimIndent()
        Files.writeString(tmp.resolve(ConductorMarker.MARKER_FILE), legacy)

        val read = ConductorMarker.readConfig(tmp)
        assertNotNull(read)
        assertEquals("claude", read!!.startupCommand)
        assertEquals("squash", read.defaultMergeStrategy)
        assertEquals("legacy-ws", read.name)
        assertNull(read.pr)
    }

    @Test
    fun `preserves unknown keys through write`(@TempDir tmp: Path) {
        val withExtras = """
            {
              "startupCommand": "",
              "openTerminalOnStart": false,
              "defaultMergeStrategy": "",
              "experimental": {"foo": 1}
            }
        """.trimIndent()
        Files.writeString(tmp.resolve(ConductorMarker.MARKER_FILE), withExtras)

        ConductorMarker.writePrState(
            tmp,
            ConductorMarker.PrState(
                forge = "github", number = 1, url = "u", baseBranch = "main",
                headBranch = "f", state = "open", lastCheckedAt = "t",
            ),
        )

        val text = Files.readString(tmp.resolve(ConductorMarker.MARKER_FILE))
        assertTrue(text.contains("experimental"), "unknown keys should survive write-back: $text")
    }
}
