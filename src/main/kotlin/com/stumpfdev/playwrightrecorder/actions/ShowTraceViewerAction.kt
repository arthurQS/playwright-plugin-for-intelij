package com.stumpfdev.playwrightrecorder.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.stumpfdev.playwrightrecorder.service.RecorderService

class ShowTraceViewerAction : AnAction() {
    init {
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.ShowAsTree
    }

    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        descriptor.title = "Select Playwright Trace (.zip)"
        descriptor.withFileFilter { file -> file.extension.equals("zip", ignoreCase = true) }

        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
        val files = chooser.choose(project)
        val vFile = files.firstOrNull()
        if (vFile == null) {
            Messages.showInfoMessage(project, "No trace selected.", "Playwright Recorder")
            return
        }

        val service = project.getService(RecorderService::class.java)
        val started = service.showTraceViewer(VfsUtil.virtualToIoFile(vFile))
        if (!started) {
            Messages.showErrorDialog(project, "Failed to open trace viewer.", "Playwright Recorder")
        }
    }
}
