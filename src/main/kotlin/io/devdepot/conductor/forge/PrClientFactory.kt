package io.devdepot.conductor.forge

import com.intellij.openapi.project.Project
import io.devdepot.conductor.settings.ConductorSettings

object PrClientFactory {

    fun forForge(project: Project, forge: Forge): PrClient? {
        val settings = ConductorSettings.get(project)
        return when (forge) {
            Forge.GITHUB -> GitHubPrClient(settings.ghCliCommand)
            Forge.BITBUCKET -> BitbucketPrClient(settings.bbCliCommand)
            Forge.NONE -> null
        }
    }
}
