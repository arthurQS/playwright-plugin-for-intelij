package com.stumpfdev.playwrightrecorder.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class EditorLocatorHighlighter(private val project: Project) : Disposable {
    private val recorder = project.getService(RecorderService::class.java)
    private var lastLocator: String? = null
    private var lastTriggerAt: Long = 0

    init {
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                handleCaret(event)
            }
        }, this)
    }

    private fun handleCaret(event: CaretEvent) {
        val editor = event.editor
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        val lineText = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
        val locator = extractLocator(lineText) ?: return
        val url = recorder.lastStartUrl
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        if (locator == lastLocator && now - lastTriggerAt < 750) return
        lastLocator = locator
        lastTriggerAt = now
        recorder.startPreview(url, locator)
    }

    private fun extractLocator(line: String): String? {
        val jsLocator = Regex("page\\.locator\\(\\s*(['\"])(.+?)\\1")
        val jsTestId = Regex("page\\.getByTestId\\(\\s*(['\"])(.+?)\\1")
        val pyLocator = Regex("page\\.locator\\(\\s*(['\"])(.+?)\\1")
        val pyTestId = Regex("page\\.get_by_test_id\\(\\s*(['\"])(.+?)\\1")

        jsTestId.find(line)?.let { return "data-testid=${it.groupValues[2]}" }
        pyTestId.find(line)?.let { return "data-testid=${it.groupValues[2]}" }
        jsLocator.find(line)?.let { return it.groupValues[2] }
        pyLocator.find(line)?.let { return it.groupValues[2] }
        return null
    }

    override fun dispose() {
    }
}
