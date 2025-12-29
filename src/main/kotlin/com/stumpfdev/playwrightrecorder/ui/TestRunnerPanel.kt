package com.stumpfdev.playwrightrecorder.ui

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.stumpfdev.playwrightrecorder.util.NodeLocator
import com.stumpfdev.playwrightrecorder.util.ProjectDetector
import com.stumpfdev.playwrightrecorder.util.PythonLocator
import com.stumpfdev.playwrightrecorder.util.TargetLanguage
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.nio.charset.StandardCharsets
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class TestRunnerPanel(private val project: Project) : JPanel(BorderLayout()) {
    private enum class TestStatus { UNKNOWN, RUNNING, PASSED, FAILED, SKIPPED }

    private data class TestNodeData(
        val id: String,
        val name: String,
        val file: String,
        var status: TestStatus = TestStatus.UNKNOWN,
        var durationMs: Long? = null
    )

    private val runnerLabel = JLabel("Runner: auto")
    private val statusLabel = JLabel("Status: idle")
    private val discoverButton = JButton("Discover")
    private val runButton = JButton("Run All")
    private val stopButton = JButton("Stop")
    private val rootNode = DefaultMutableTreeNode("Tests")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = JTree(treeModel)
    private val outputArea = JBTextArea()
    private val testNodes = mutableMapOf<String, DefaultMutableTreeNode>()
    private val fileNodes = mutableMapOf<String, DefaultMutableTreeNode>()
    private var processHandler: OSProcessHandler? = null

    init {
        border = JBUI.Borders.empty(8)
        add(createToolbar(), BorderLayout.NORTH)
        add(createCenter(), BorderLayout.CENTER)
        refreshRunnerLabel()
    }

    private fun createToolbar(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        panel.add(runnerLabel)
        panel.add(JLabel("|"))
        panel.add(statusLabel)
        panel.add(discoverButton)
        panel.add(runButton)
        panel.add(stopButton)

        discoverButton.addActionListener { discoverTests() }
        runButton.addActionListener { runTests() }
        stopButton.addActionListener { stopTests() }
        stopButton.isEnabled = false
        return panel
    }

    private fun createCenter(): JPanel {
        val panel = JPanel(BorderLayout())
        tree.cellRenderer = RunnerTreeRenderer()
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)

        outputArea.isEditable = false
        outputArea.emptyText.text = "Test runner output"
        val outputScroll = JBScrollPane(outputArea)
        outputScroll.preferredSize = JBUI.size(400, 160)
        panel.add(outputScroll, BorderLayout.SOUTH)
        return panel
    }

    private fun refreshRunnerLabel() {
        val detected = ProjectDetector.detect(project)
        runnerLabel.text = when {
            detected.hasJavaScript && detected.hasPython -> "Runner: auto (JS/Python)"
            detected.hasPython -> "Runner: pytest"
            detected.hasJavaScript -> "Runner: playwright"
            else -> "Runner: none"
        }
    }

    private fun discoverTests() {
        val target = ProjectDetector.detect(project).preferred
        statusLabel.text = "Status: discovering"
        discoverButton.isEnabled = false
        runButton.isEnabled = false
        outputArea.text = ""

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Discovering tests", true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val output = runDiscoverCommand(target)
                val tests = when (target) {
                    TargetLanguage.JAVASCRIPT -> parsePlaywrightList(output)
                    TargetLanguage.PYTHON -> parsePytestList(output)
                }
                ApplicationManager.getApplication().invokeLater {
                    populateTree(tests)
                    statusLabel.text = "Status: idle"
                    discoverButton.isEnabled = true
                    runButton.isEnabled = tests.isNotEmpty()
                }
            }
        })
    }

    private fun runTests() {
        val target = ProjectDetector.detect(project).preferred
        if (processHandler != null) return
        statusLabel.text = "Status: running"
        runButton.isEnabled = false
        discoverButton.isEnabled = false
        stopButton.isEnabled = true
        markAllRunning()
        outputArea.text = ""

        val command = buildRunCommand(target)
        if (command == null) {
            statusLabel.text = "Status: idle"
            runButton.isEnabled = true
            discoverButton.isEnabled = true
            stopButton.isEnabled = false
            return
        }
        val workDir = File(project.basePath ?: ".")
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val handler = OSProcessHandler(process, command.joinToString(" "), StandardCharsets.UTF_8)
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {}

            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                val text = event.text
                appendOutput(text)
                when (target) {
                    TargetLanguage.JAVASCRIPT -> handlePlaywrightOutput(text)
                    TargetLanguage.PYTHON -> handlePytestOutput(text)
                }
            }

            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}

            override fun processTerminated(event: ProcessEvent) {
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Status: idle"
                    runButton.isEnabled = true
                    discoverButton.isEnabled = true
                    stopButton.isEnabled = false
                    processHandler = null
                }
            }
        })
        processHandler = handler
        handler.startNotify()
    }

    private fun stopTests() {
        processHandler?.destroyProcess()
        processHandler = null
        statusLabel.text = "Status: idle"
        stopButton.isEnabled = false
        runButton.isEnabled = true
        discoverButton.isEnabled = true
    }

    private fun runDiscoverCommand(target: TargetLanguage): String {
        val command = buildDiscoverCommand(target) ?: return ""
        val workDir = File(project.basePath ?: ".")
        return try {
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildDiscoverCommand(target: TargetLanguage): List<String>? {
        return when (target) {
            TargetLanguage.JAVASCRIPT -> listOf(NodeLocator.resolveNpx(project), "playwright", "test", "--list")
            TargetLanguage.PYTHON -> listOf(PythonLocator.resolvePythonExecutable(project), "-m", "pytest", "--collect-only", "-q")
        }
    }

    private fun buildRunCommand(target: TargetLanguage): List<String>? {
        return when (target) {
            TargetLanguage.JAVASCRIPT -> listOf(NodeLocator.resolveNpx(project), "playwright", "test", "--reporter=line")
            TargetLanguage.PYTHON -> listOf(PythonLocator.resolvePythonExecutable(project), "-m", "pytest", "-q", "-vv", "--durations=0")
        }
    }

    private fun parsePlaywrightList(output: String): List<TestNodeData> {
        val tests = mutableListOf<TestNodeData>()
        output.lineSequence().forEach { line ->
            val cleaned = line.trim()
            if (!cleaned.contains("›")) return@forEach
            val noBrowser = cleaned.replace(Regex("^\\[[^\\]]+\\]\\s*"), "")
            val parts = noBrowser.split("›").map { it.trim() }
            if (parts.size < 2) return@forEach
            val name = parts.last()
            val filePart = parts.dropLast(1).joinToString(" › ")
            val filePath = filePart.replace(Regex(":\\d+:\\d+$"), "")
            val id = "$filePath|$name"
            tests.add(TestNodeData(id, name, filePath))
        }
        return tests
    }

    private fun parsePytestList(output: String): List<TestNodeData> {
        val tests = mutableListOf<TestNodeData>()
        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.contains("::")) return@forEach
            val file = trimmed.substringBefore("::")
            val name = trimmed.substringAfter("::")
            tests.add(TestNodeData(trimmed, name, file))
        }
        return tests
    }

    private fun populateTree(tests: List<TestNodeData>) {
        rootNode.removeAllChildren()
        testNodes.clear()
        fileNodes.clear()
        val basePath = project.basePath?.let { File(it).absolutePath } ?: ""
        tests.forEach { test ->
            val fileLabel = if (basePath.isNotBlank()) {
                test.file.removePrefix(basePath).trimStart('\\', '/')
            } else {
                test.file
            }
            val fileNode = fileNodes.getOrPut(fileLabel) {
                val node = DefaultMutableTreeNode(fileLabel)
                rootNode.add(node)
                node
            }
            val testNode = DefaultMutableTreeNode(test)
            testNodes[test.id] = testNode
            fileNode.add(testNode)
        }
        treeModel.reload()
    }

    private fun markAllRunning() {
        testNodes.values.forEach { node ->
            val data = node.userObject as? TestNodeData ?: return@forEach
            data.status = TestStatus.RUNNING
            data.durationMs = null
            treeModel.nodeChanged(node)
        }
    }

    private fun appendOutput(text: String) {
        ApplicationManager.getApplication().invokeLater {
            outputArea.append(text)
            outputArea.caretPosition = outputArea.document.length
        }
    }

    private fun handlePlaywrightOutput(text: String) {
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val symbol = when {
                trimmed.startsWith("✓") -> "✓"
                trimmed.startsWith("✘") -> "✘"
                trimmed.startsWith("×") -> "×"
                else -> null
            } ?: return@forEach
            val rest = trimmed.removePrefix(symbol).trim()
            val noBrowser = rest.replace(Regex("^\\[[^\\]]+\\]\\s*"), "")
            val durationMs = parseDurationMs(noBrowser)
            val noDuration = noBrowser.replace(Regex("\\s*\\([0-9.]+(ms|s)\\)\\s*$"), "").trim()
            val parts = noDuration.split("›").map { it.trim() }
            if (parts.size < 2) return@forEach
            val name = parts.last()
            val filePart = parts.dropLast(1).joinToString(" › ")
            val filePath = filePart.replace(Regex(":\\d+:\\d+$"), "")
            val id = "$filePath|$name"
            updateTestNode(id, if (symbol == "✓") TestStatus.PASSED else TestStatus.FAILED, durationMs)
        }
    }

    private fun handlePytestOutput(text: String) {
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            val match = Regex("^(\\S+::\\S+)\\s+(PASSED|FAILED|SKIPPED|XFAIL|XPASS)").find(trimmed)
            if (match != null) {
                val id = match.groupValues[1]
                val status = when (match.groupValues[2]) {
                    "PASSED" -> TestStatus.PASSED
                    "FAILED" -> TestStatus.FAILED
                    "SKIPPED", "XFAIL" -> TestStatus.SKIPPED
                    "XPASS" -> TestStatus.FAILED
                    else -> TestStatus.UNKNOWN
                }
                updateTestNode(id, status, null)
                return@forEach
            }
            val durationMatch = Regex("^(\\d+(?:\\.\\d+)?)(ms|s)\\s+call\\s+(\\S+)$").find(trimmed)
            if (durationMatch != null) {
                val value = durationMatch.groupValues[1].toDoubleOrNull() ?: return@forEach
                val unit = durationMatch.groupValues[2]
                val id = durationMatch.groupValues[3]
                val ms = if (unit == "s") (value * 1000).toLong() else value.toLong()
                updateTestNode(id, null, ms)
            }
        }
    }

    private fun updateTestNode(id: String, status: TestStatus?, durationMs: Long?) {
        val node = testNodes[id] ?: return
        val data = node.userObject as? TestNodeData ?: return
        if (status != null) {
            data.status = status
        }
        if (durationMs != null) {
            data.durationMs = durationMs
        }
        ApplicationManager.getApplication().invokeLater {
            treeModel.nodeChanged(node)
        }
    }

    private fun parseDurationMs(text: String): Long? {
        val match = Regex("\\(([0-9.]+)(ms|s)\\)").find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (match.groupValues[2] == "s") (value * 1000).toLong() else value.toLong()
    }

    private inner class RunnerTreeRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): java.awt.Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return this
            val data = node.userObject
            if (data is TestNodeData) {
                text = buildString {
                    append(data.name)
                    data.durationMs?.let { append(" (${it}ms)") }
                }
                icon = when (data.status) {
                    TestStatus.PASSED -> com.intellij.icons.AllIcons.General.InspectionsOK
                    TestStatus.FAILED -> com.intellij.icons.AllIcons.General.Error
                    TestStatus.SKIPPED -> com.intellij.icons.AllIcons.General.Warning
                    TestStatus.RUNNING -> com.intellij.icons.AllIcons.Actions.Execute
                    TestStatus.UNKNOWN -> null
                }
                foreground = when (data.status) {
                    TestStatus.PASSED -> JBColor(0x2E7D32, 0x6FBF73)
                    TestStatus.FAILED -> JBColor(0xC62828, 0xFF8A80)
                    TestStatus.SKIPPED -> JBColor(0xB58900, 0xFFD54F)
                    else -> foreground
                }
            }
            return this
        }
    }
}
