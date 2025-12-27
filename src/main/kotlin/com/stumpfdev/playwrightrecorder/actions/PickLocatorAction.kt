package com.stumpfdev.playwrightrecorder.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.stumpfdev.playwrightrecorder.service.RecorderService

class PickLocatorAction : AnAction() {
    init {
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.Find
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
        val url = service.lastStartUrl
        if (url.isBlank()) {
            Messages.showWarningDialog(project, "Set a start URL before picking a locator.", "Pick Locator")
            return
        }
        val started = service.beginPickLocator(url)
        if (!started) {
            Messages.showErrorDialog(project, "Failed to start locator picker.", "Pick Locator")
        } else {
            Messages.showInfoMessage(project, "Click an element in the browser to capture a locator.", "Pick Locator")
        }
    }
}
