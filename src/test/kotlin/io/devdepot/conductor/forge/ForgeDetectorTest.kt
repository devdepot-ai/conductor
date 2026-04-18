package io.devdepot.conductor.forge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForgeDetectorTest {

    @Test
    fun `github https`() {
        assertEquals(Forge.GITHUB, ForgeDetector.fromUrl("https://github.com/org/repo.git"))
    }

    @Test
    fun `github ssh shorthand`() {
        assertEquals(Forge.GITHUB, ForgeDetector.fromUrl("git@github.com:org/repo.git"))
    }

    @Test
    fun `github enterprise`() {
        assertEquals(Forge.GITHUB, ForgeDetector.fromUrl("https://github.internal.company.com/org/repo.git"))
    }

    @Test
    fun `bitbucket cloud`() {
        assertEquals(Forge.BITBUCKET, ForgeDetector.fromUrl("https://bitbucket.org/team/repo.git"))
    }

    @Test
    fun `bitbucket ssh`() {
        assertEquals(Forge.BITBUCKET, ForgeDetector.fromUrl("git@bitbucket.org:team/repo.git"))
    }

    @Test
    fun `bitbucket data center`() {
        assertEquals(Forge.BITBUCKET, ForgeDetector.fromUrl("https://bitbucket.acme.internal/scm/team/repo.git"))
    }

    @Test
    fun `ssh scheme form`() {
        assertEquals(Forge.GITHUB, ForgeDetector.fromUrl("ssh://git@github.com:22/org/repo.git"))
    }

    @Test
    fun `unknown host`() {
        assertEquals(Forge.NONE, ForgeDetector.fromUrl("https://gitlab.com/org/repo.git"))
    }

    @Test
    fun `local path`() {
        assertEquals(Forge.NONE, ForgeDetector.fromUrl("/tmp/repo.git"))
    }

    @Test
    fun `blank url`() {
        assertEquals(Forge.NONE, ForgeDetector.fromUrl(""))
    }
}
