package com.stumpfdev.playwrightrecorder.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class RecorderStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = RecorderStatusService.ID

    override fun getDisplayName(): String = "Playwright Recorder"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return project.getService(RecorderStatusService::class.java)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }
}
