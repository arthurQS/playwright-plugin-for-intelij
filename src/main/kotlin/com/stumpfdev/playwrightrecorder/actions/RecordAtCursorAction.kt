package com.stumpfdev.playwrightrecorder.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.command.WriteCommandAction
import com.stumpfdev.playwrightrecorder.service.RecorderService

class RecordAtCursorAction : AnAction() {
    init {
        templatePresentation.icon = com.intellij.icons.AllIcons.Actions.Edit
    }

    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            Messages.showInfoMessage(project, "Open an editor to insert steps.", "Playwright Recorder")
            return
        }
        val service = project.getService(RecorderService::class.java)
        val caret = editor.caretModel.primaryCaret
        val document = editor.document
        val line = document.getLineNumber(caret.offset)
        val indent = detectIndent(document, line)
        val snippet = service.getStepsSnippet(indent)
        if (snippet.isBlank()) {
            Messages.showInfoMessage(project, "No recorded steps available.", "Playwright Recorder")
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(caret.offset, snippet + "\n")
        }
    }

    private fun detectIndent(document: Document, line: Int): String {
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.getText(com.intellij.openapi.util.TextRange(start, end))
        val prefix = text.takeWhile { it == ' ' || it == '\t' }
        return prefix
    }
}
