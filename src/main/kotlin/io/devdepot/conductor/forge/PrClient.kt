package io.devdepot.conductor.forge

import java.nio.file.Path

data class PrRef(val number: Int, val url: String)

data class PrRemoteState(val state: String, val mergedAt: String?, val url: String?)

sealed class PrOutcome<out T> {
    data class Ok<T>(val value: T) : PrOutcome<T>()
    data class Err(val message: String) : PrOutcome<Nothing>()
}

/**
 * Forge-agnostic pull-request client. Implementations shell out to `gh` / `bb`
 * and surface outcomes as [PrOutcome]. Created via [PrClientFactory].
 */
interface PrClient {
    val forge: Forge

    fun create(
        cwd: Path,
        head: String,
        base: String,
        title: String,
        body: String,
    ): PrOutcome<PrRef>

    fun fetchState(cwd: Path, number: Int): PrOutcome<PrRemoteState>
}
