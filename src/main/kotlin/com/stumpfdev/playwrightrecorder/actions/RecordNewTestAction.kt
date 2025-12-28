package com.stumpfdev.playwrightrecorder.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.stumpfdev.playwrightrecorder.service.RecorderService
import com.stumpfdev.playwrightrecorder.util.FileUtil
import com.stumpfdev.playwrightrecorder.util.ProjectDetector
import com.stumpfdev.playwrightrecorder.settings.PlaywrightSettingsService
import java.io.File

class RecordNewTestAction : AnAction() {
    init {
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.AddFile
    }

    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(RecorderService::class.java)
        val generated = service.lastGeneratedFile
        if (generated != null && generated.exists()) {
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generated)
            if (vFile != null) {
                FileEditorManager.getInstance(project).openFile(vFile, true)
                return
            }
        }
        val basePath = project.basePath ?: return
        val isTs = ProjectDetector.isTypeScriptProject(project)
        val ext = service.getSuggestedFileExtension(isTs)
        val settings = project.getService(PlaywrightSettingsService::class.java).get()
        val testsDir = File(basePath, settings.testsDir.ifBlank { "tests" })
        val content = service.buildNewTestFileContent()
        val file = createUniqueTestFile(testsDir, ext)
        WriteCommandAction.runWriteCommandAction(project) {
            FileUtil.ensureDir(testsDir)
            file.writeText(content)
        }

        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (vFile != null) {
            FileEditorManager.getInstance(project).openFile(vFile, true)
        } else {
            Messages.showInfoMessage(project, "Test file created at ${file.absolutePath}", "Playwright Recorder")
        }
    }

    private fun createUniqueTestFile(dir: File, ext: String): File {
        var idx = 0
        while (true) {
            val name = if (idx == 0) "recorded$ext" else "recorded-$idx$ext"
            val candidate = File(dir, name)
            if (!candidate.exists()) return candidate
            idx++
        }
    }
}
