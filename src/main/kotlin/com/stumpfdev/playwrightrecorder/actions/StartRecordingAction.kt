package com.stumpfdev.playwrightrecorder.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.stumpfdev.playwrightrecorder.service.RecorderService
import com.stumpfdev.playwrightrecorder.util.TargetLanguage

class StartRecordingAction : AnAction() {
    init {
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.Execute
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
        e.presentation.isEnabled = !service.isRecording()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(RecorderService::class.java)
        if (service.isRecording()) {
            Messages.showInfoMessage(project, "Recording is already running.", "Playwright Recorder")
            return
        }
        val check = service.checkPlaywrightAvailability(service.selectedLanguage)
        if (!check.available) {
            val choice = Messages.showDialog(
                project,
                "${check.message}\nInstall Playwright now?",
                "Playwright Recorder",
                arrayOf("Install JS", "Install Python", "Cancel"),
                0,
                null
            )
            when (choice) {
                0 -> {
                    service.setSelectedLanguage(TargetLanguage.JAVASCRIPT)
                    installAndStart(project, service, e.getData(CommonDataKeys.EDITOR))
                    return
                }
                1 -> {
                    service.setSelectedLanguage(TargetLanguage.PYTHON)
                    installAndStart(project, service, e.getData(CommonDataKeys.EDITOR))
                    return
                }
                else -> return
            }
        }
        startRecordingFlow(project, service, e.getData(CommonDataKeys.EDITOR))
    }

    private fun installAndStart(project: com.intellij.openapi.project.Project, service: RecorderService, editor: com.intellij.openapi.editor.Editor?) {
        val target = service.selectedLanguage
        service.installPlaywright(target) { ok ->
            ApplicationManager.getApplication().invokeLater {
                if (!ok) {
                    Messages.showErrorDialog(project, "Playwright installation failed.", "Playwright Recorder")
                    return@invokeLater
                }
                val recheck = service.checkPlaywrightAvailability(target)
                if (!recheck.available) {
                    Messages.showWarningDialog(project, recheck.message, "Playwright Recorder")
                    return@invokeLater
                }
                Messages.showInfoMessage(project, "Playwright installed successfully.", "Playwright Recorder")
                startRecordingFlow(project, service, editor)
            }
        }
    }

    private fun startRecordingFlow(project: com.intellij.openapi.project.Project, service: RecorderService, editor: com.intellij.openapi.editor.Editor?) {
        if (editor != null) {
            val liveChoice = Messages.showYesNoDialog(
                project,
                "Insert steps into the current editor while recording?",
                "Playwright Recorder",
                null
            )
            if (liveChoice == Messages.YES) {
                service.setLiveInsertEditor(editor)
            } else {
                service.setLiveInsertEditor(null)
            }
        }
        val url = Messages.showInputDialog(project, "Start URL (optional):", "Start Recording", null)
        if (!url.isNullOrBlank()) {
            service.setStartUrl(url)
            service.startSession(url)
        }
        val started = service.startRecording(service.selectedLanguage, url)
        if (!started) {
            Messages.showErrorDialog(project, "Failed to start Playwright codegen.", "Playwright Recorder")
        }
    }
}
