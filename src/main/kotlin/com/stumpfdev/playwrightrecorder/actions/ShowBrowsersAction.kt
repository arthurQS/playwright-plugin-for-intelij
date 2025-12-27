package com.stumpfdev.playwrightrecorder.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.stumpfdev.playwrightrecorder.service.RecorderService

class ShowBrowsersAction : AnAction() {
    init {
        templatePresentation.icon = com.intellij.icons.AllIcons.General.Web
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
        e.presentation.isEnabled = service.isRecording()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(RecorderService::class.java)
        if (service.isRecording()) {
            val pid = service.lastPid?.toString() ?: "unknown"
            val cmd = if (service.lastCommand.isBlank()) "unknown" else service.lastCommand
            Messages.showInfoMessage(project, "Recorder is running.\nPID: $pid\nCommand: $cmd", "Playwright Recorder")
        } else {
            Messages.showInfoMessage(project, "No Playwright browsers detected.", "Playwright Recorder")
        }
    }
}
