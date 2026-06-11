package io.devdepot.conductor.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitTest {

    private val remotes = listOf("origin", "upstream")

    @Test
    fun `local ref is returned unchanged`() {
        assertEquals("feature", Git.localNameForRef("feature", remotes))
        assertEquals("feat/foo", Git.localNameForRef("feat/foo", remotes))
    }

    @Test
    fun `remote-tracking ref strips its remote prefix`() {
        assertEquals("feature", Git.localNameForRef("origin/feature", remotes))
        assertEquals("feat/foo", Git.localNameForRef("origin/feat/foo", remotes))
        assertEquals("bar", Git.localNameForRef("upstream/bar", remotes))
    }

    @Test
    fun `only a real remote prefix is stripped`() {
        // No remote named "feature" — the slash belongs to the branch name.
        assertEquals("feature/x", Git.localNameForRef("feature/x", remotes))
    }

    @Test
    fun `no remotes leaves ref untouched`() {
        assertEquals("origin/feature", Git.localNameForRef("origin/feature", emptyList()))
    }
}
