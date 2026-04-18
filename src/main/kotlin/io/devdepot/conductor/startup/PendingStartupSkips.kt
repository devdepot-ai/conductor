package io.devdepot.conductor.startup

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object PendingStartupSkips {
    private val paths = ConcurrentHashMap.newKeySet<String>()

    fun markSkip(path: Path) {
        paths.add(normalize(path))
    }

    fun consumeSkip(path: Path): Boolean =
        paths.remove(normalize(path))

    private fun normalize(path: Path): String =
        path.toAbsolutePath().normalize().toString()
}
