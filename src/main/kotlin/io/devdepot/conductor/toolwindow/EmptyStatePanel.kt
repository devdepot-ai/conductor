package io.devdepot.conductor.toolwindow

import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Shown when the project is neither a Conductor trunk nor a workspace
 * (e.g. a non-git directory or a linked worktree without the marker).
 */
class EmptyStatePanel : SimpleToolWindowPanel(true, true) {

    init {
        val content = JPanel(BorderLayout())

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
        }
        stack.add(center(JBLabel("Open a git repository to use Conductor.")))
        stack.add(
            center(
                JBLabel("The Conductor tool window activates automatically.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            ),
        )
        content.add(stack, BorderLayout.CENTER)
        setContent(content)
    }

    private fun center(label: JBLabel): JPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
        label.horizontalAlignment = SwingConstants.CENTER
        add(label)
        isOpaque = false
    }
}
