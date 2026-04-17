package io.devdepot.conductor.ide

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ProjectOpener {

    fun openInNewWindow(path: Path): Project? {
        val task = OpenProjectTask {
            forceOpenInNewFrame = true
        }
        return ProjectManagerEx.getInstanceEx().openProject(path, task)
    }

    fun focusIfOpen(path: Path): Project? {
        val normalized = path.toAbsolutePath().normalize().toString()
        return ProjectManager.getInstance().openProjects.firstOrNull {
            it.basePath?.let { p -> Path.of(p).toAbsolutePath().normalize().toString() } == normalized
        }
    }

    fun closeProject(project: Project) {
        ProjectManager.getInstance().closeAndDispose(project)
    }
}
