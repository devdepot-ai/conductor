package io.devdepot.conductor.forge

enum class Forge(val id: String, val displayName: String) {
    GITHUB("github", "GitHub"),
    BITBUCKET("bitbucket", "Bitbucket"),
    NONE("none", "no forge");

    companion object {
        fun fromId(id: String?): Forge = values().firstOrNull { it.id == id } ?: NONE
    }
}
