package com.stumpfdev.playwrightrecorder.ui

import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.components.Service
import javax.swing.JComponent
import javax.swing.JLabel

@Service(Service.Level.PROJECT)
class RecorderStatusService(private val project: Project) : CustomStatusBarWidget {
    private val label = JLabel("Playwright: idle")

    fun setStatus(text: String) {
        label.text = text
        val statusBar = WindowManager.getInstance().getStatusBar(project)
        statusBar?.updateWidget(ID)
    }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
    }

    override fun getComponent(): JComponent = label

    override fun dispose() {
    }

    companion object {
        const val ID = "PlaywrightRecorderStatus"
    }
}
