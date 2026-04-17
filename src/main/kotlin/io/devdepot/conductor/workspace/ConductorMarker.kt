package io.devdepot.conductor.workspace

import java.nio.file.Files
import java.nio.file.Path

object ConductorMarker {
    private const val DIR_NAME = ".conductor"

    fun write(worktreeRoot: Path) {
        val marker = worktreeRoot.resolve(DIR_NAME)
        if (!Files.exists(marker)) {
            Files.createDirectories(marker)
        }
    }

    fun isPresent(path: Path): Boolean =
        Files.isDirectory(path.resolve(DIR_NAME))
}
