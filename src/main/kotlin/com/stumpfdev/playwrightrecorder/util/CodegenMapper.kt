package com.stumpfdev.playwrightrecorder.util

import com.stumpfdev.playwrightrecorder.model.RecordedStep
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange

object CodegenMapper {
    fun extractSteps(target: TargetLanguage, raw: String): List<RecordedStep> {
        val lines = raw.lines()
        val filtered = when (target) {
            TargetLanguage.JAVASCRIPT -> lines.filter { it.trimStart().startsWith("await ") || it.trimStart().startsWith("page.") }
            TargetLanguage.PYTHON -> lines.filter { it.trimStart().startsWith("page.") }
        }
        return filtered.map { RecordedStep(it.trimEnd()) }
    }

    fun stepsToSnippet(target: TargetLanguage, steps: List<RecordedStep>, indent: String): String {
        return steps.joinToString("\n") { step ->
            val text = step.text.trimStart()
            indent + text
        }
    }

    fun defaultTestFile(target: TargetLanguage, steps: List<RecordedStep>): String {
        return when (target) {
            TargetLanguage.JAVASCRIPT -> {
                val body = steps.joinToString("\n") { "  " + it.text.trimStart() }
                """
import { test, expect } from '@playwright/test';

test('recorded', async ({ page }) => {
$body
});
""".trimStart()
            }
            TargetLanguage.PYTHON -> {
                val body = steps.joinToString("\n") { "    " + it.text.trimStart() }
                """
from playwright.sync_api import expect

def test_recorded(page):
$body
""".trimStart()
            }
        }
    }

    fun fileExtension(projectIsTs: Boolean, target: TargetLanguage): String {
        return when (target) {
            TargetLanguage.JAVASCRIPT -> if (projectIsTs) ".ts" else ".js"
            TargetLanguage.PYTHON -> ".py"
        }
    }

    fun detectIndent(document: Document, line: Int): String {
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.getText(TextRange(start, end))
        return text.takeWhile { it == ' ' || it == '\t' }
    }

    fun mergeStepsIntoFile(target: TargetLanguage, content: String, steps: List<RecordedStep>): String? {
        if (steps.isEmpty()) return null
        val existingSteps = extractSteps(target, content)
        val existingSet = existingSteps.map { normalizeStep(it.text) }.toSet()
        val newSteps = steps.filter { normalizeStep(it.text).isNotBlank() && !existingSet.contains(normalizeStep(it.text)) }
        if (newSteps.isEmpty()) return null
        return when (target) {
            TargetLanguage.JAVASCRIPT -> mergeIntoJs(content, newSteps)
            TargetLanguage.PYTHON -> mergeIntoPython(content, newSteps)
        }
    }

    private fun mergeIntoJs(content: String, steps: List<RecordedStep>): String {
        if (content.isBlank()) {
            return defaultTestFile(TargetLanguage.JAVASCRIPT, steps)
        }
        val lines = content.lines().toMutableList()
        val closingIndex = lines.indexOfLast { it.trim() == "});" }
        if (closingIndex >= 0) {
            val closingIndent = leadingWhitespace(lines[closingIndex])
            val stepIndent = closingIndent + "  "
            val snippet = stepsToSnippet(TargetLanguage.JAVASCRIPT, steps, stepIndent)
            val insertLines = snippet.lines()
            lines.addAll(closingIndex, insertLines)
            return lines.joinToString("\n")
        }
        val needsImport = !content.contains("@playwright/test")
        val block = buildJsTestBlock(steps, needsImport)
        return content.trimEnd() + "\n\n" + block.trimEnd() + "\n"
    }

    private fun mergeIntoPython(content: String, steps: List<RecordedStep>): String {
        if (content.isBlank()) {
            return defaultTestFile(TargetLanguage.PYTHON, steps)
        }
        val lines = content.lines().toMutableList()
        val defIndex = lines.indexOfFirst { it.trimStart().startsWith("def test") }
        if (defIndex >= 0) {
            val defIndent = leadingWhitespace(lines[defIndex])
            val bodyIndent = findPythonBodyIndent(lines, defIndex, defIndent)
            val insertIndex = findPythonBlockEnd(lines, defIndex, defIndent)
            val snippet = stepsToSnippet(TargetLanguage.PYTHON, steps, bodyIndent)
            val insertLines = snippet.lines()
            lines.addAll(insertIndex, insertLines)
            return lines.joinToString("\n")
        }
        val needsImport = !content.contains("from playwright.sync_api")
        val block = buildPythonTestBlock(steps, needsImport)
        return content.trimEnd() + "\n\n" + block.trimEnd() + "\n"
    }

    private fun buildJsTestBlock(steps: List<RecordedStep>, includeImport: Boolean): String {
        val body = steps.joinToString("\n") { "  " + it.text.trimStart() }
        val header = if (includeImport) "import { test, expect } from '@playwright/test';\n\n" else ""
        return header + """
test('recorded', async ({ page }) => {
$body
});
""".trimStart()
    }

    private fun buildPythonTestBlock(steps: List<RecordedStep>, includeImport: Boolean): String {
        val body = steps.joinToString("\n") { "    " + it.text.trimStart() }
        val header = if (includeImport) "from playwright.sync_api import expect\n\n" else ""
        return header + """
def test_recorded(page):
$body
""".trimStart()
    }

    private fun findPythonBodyIndent(lines: List<String>, defIndex: Int, defIndent: String): String {
        for (i in defIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.trim().isEmpty()) continue
            val indent = leadingWhitespace(line)
            if (indent.length > defIndent.length) {
                return indent
            }
            break
        }
        return defIndent + "    "
    }

    private fun findPythonBlockEnd(lines: List<String>, defIndex: Int, defIndent: String): Int {
        for (i in defIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.trim().isEmpty()) continue
            val indent = leadingWhitespace(line)
            if (indent.length <= defIndent.length && line.trimStart().startsWith("def ")) {
                return i
            }
        }
        return lines.size
    }

    private fun leadingWhitespace(line: String): String {
        return line.takeWhile { it == ' ' || it == '\t' }
    }

    private fun normalizeStep(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
    }
}
