package io.devdepot.conductor.workspace

import java.nio.file.Path

data class Workspace(
    val name: String,
    val branch: String,
    val path: Path,
    val isCurrent: Boolean,
)
