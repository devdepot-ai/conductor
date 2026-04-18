package io.devdepot.conductor.forge

import io.devdepot.conductor.git.Git
import java.nio.file.Path

/**
 * Resolves which forge a repository publishes to by parsing `origin`'s URL.
 * Pure function of the URL host — no network calls. First-pass support:
 * GitHub (cloud + enterprise) and Bitbucket (cloud + Data Center).
 */
object ForgeDetector {

    fun detect(trunk: Path): Forge {
        val url = originUrl(trunk) ?: return Forge.NONE
        return fromUrl(url)
    }

    fun originUrl(trunk: Path): String? {
        val r = Git.exec(listOf("remote", "get-url", "origin"), trunk)
        if (!r.ok) return null
        return r.stdout.trim().takeIf { it.isNotBlank() }
    }

    fun fromUrl(url: String): Forge {
        val host = extractHost(url) ?: return Forge.NONE
        val lower = host.lowercase()
        return when {
            lower == "github.com" || lower.startsWith("github.") || lower.contains(".github.") -> Forge.GITHUB
            lower == "bitbucket.org" || lower.startsWith("bitbucket.") || lower.contains(".bitbucket.") -> Forge.BITBUCKET
            else -> Forge.NONE
        }
    }

    private fun extractHost(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        // SSH form: git@host:owner/repo.git
        val sshAt = trimmed.indexOf('@')
        val sshColon = trimmed.indexOf(':')
        if (sshAt in 0 until sshColon && !trimmed.startsWith("http")) {
            return trimmed.substring(sshAt + 1, sshColon).takeIf { it.isNotBlank() }
        }
        // ssh://user@host[:port]/owner/repo(.git)
        if (trimmed.startsWith("ssh://")) {
            val rest = trimmed.removePrefix("ssh://")
            val hostPart = rest.substringBefore('/').substringAfter('@', rest.substringBefore('/'))
            return hostPart.substringBefore(':').takeIf { it.isNotBlank() }
        }
        // http(s)://[user@]host[:port]/owner/repo(.git)
        val schemeIdx = trimmed.indexOf("://")
        if (schemeIdx >= 0) {
            val rest = trimmed.substring(schemeIdx + 3)
            val hostPart = rest.substringBefore('/').substringAfter('@', rest.substringBefore('/'))
            return hostPart.substringBefore(':').takeIf { it.isNotBlank() }
        }
        return null
    }
}
