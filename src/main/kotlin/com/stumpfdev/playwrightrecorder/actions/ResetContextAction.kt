package com.stumpfdev.playwrightrecorder.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.stumpfdev.playwrightrecorder.service.RecorderService

class ResetContextAction : AnAction() {
    init {
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.Refresh
    }

    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val service = project.getService(RecorderService::class.java)
        e.presentation.isEnabled = service.isRecording() || service.lastStartUrl.isNotBlank()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(RecorderService::class.java)
        val ok = service.resetContext()
        if (!ok) {
            Messages.showWarningDialog(project, "Recorder is not running.", "Playwright Recorder")
        }
    }
}
