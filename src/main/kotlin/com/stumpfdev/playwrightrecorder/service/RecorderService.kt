package com.stumpfdev.playwrightrecorder.service

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.stumpfdev.playwrightrecorder.model.RecordedStep
import com.stumpfdev.playwrightrecorder.util.CodegenMapper
import com.stumpfdev.playwrightrecorder.util.DetectedTargets
import com.stumpfdev.playwrightrecorder.util.NodeLocator
import com.stumpfdev.playwrightrecorder.util.ProjectDetector
import com.stumpfdev.playwrightrecorder.util.PythonLocator
import com.stumpfdev.playwrightrecorder.util.FileUtil
import com.stumpfdev.playwrightrecorder.util.TargetLanguage
import com.stumpfdev.playwrightrecorder.settings.PlaywrightSettingsService
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class RecorderService(private val project: Project) {
    data class PlaywrightCheck(val available: Boolean, val message: String)

    private var processHandler: OSProcessHandler? = null
    private var outputBuffer: StringBuilder? = null
    private var codegenOutputFile: File? = null
    private var targetOutputFile: File? = null
    private var preRecordingTargetContent: String? = null
    private var pendingInsertEditor: Editor? = null
    private var pendingOpenAfterStop: Boolean = false
    private var liveInsertEditor: Editor? = null
    private var liveInsertOffset: Int = 0
    private var liveInsertIndent: String = ""
    private var lastInsertedStepCount: Int = 0
    private var tailerRunning: Boolean = false
    private val logService = project.getService(com.stumpfdev.playwrightrecorder.ui.RecorderLogService::class.java)
    private val statusService = project.getService(com.stumpfdev.playwrightrecorder.ui.RecorderStatusService::class.java)
    private var bridgeHandler: OSProcessHandler? = null
    private var bridgeInput: BufferedWriter? = null
    private val bridgeBuffer = StringBuilder()
    var onStepsUpdated: ((List<RecordedStep>, String) -> Unit)? = null
    var onLocatorPicked: ((String) -> Unit)? = null
    var lastCommand: String = ""
        private set
    var lastPid: Long? = null
        private set
    var lastStartUrl: String = ""
        private set
    var lastPickedLocator: String = ""
        private set

    var lastGeneratedCode: String = ""
        private set
    var lastSteps: List<RecordedStep> = emptyList()
        private set
    var lastGeneratedFile: File? = null
        private set
    var selectedLanguage: TargetLanguage = TargetLanguage.JAVASCRIPT
        private set

    fun detectTargets(): DetectedTargets = ProjectDetector.detect(project)

    fun setSelectedLanguage(target: TargetLanguage) {
        selectedLanguage = target
    }

    private fun resolvePreferredLanguage(): TargetLanguage {
        return ProjectDetector.detect(project).preferred
    }

    private fun ensurePreferredLanguage() {
        selectedLanguage = resolvePreferredLanguage()
    }

    fun setStartUrl(url: String) {
        lastStartUrl = url
    }

    fun setLiveInsertEditor(editor: Editor?) {
        liveInsertEditor = editor
        lastInsertedStepCount = 0
        if (editor != null) {
            val caret = editor.caretModel.primaryCaret
            val document = editor.document
            val line = document.getLineNumber(caret.offset)
            liveInsertIndent = CodegenMapper.detectIndent(document, line)
            liveInsertOffset = caret.offset
        }
    }

    fun isRecording(): Boolean = processHandler != null

    fun startRecording(target: TargetLanguage, url: String? = null): Boolean {
        if (processHandler != null) return false
        ensurePreferredLanguage()
        lastGeneratedCode = ""
        lastSteps = emptyList()
        lastGeneratedFile = null
        if (!url.isNullOrBlank()) {
            lastStartUrl = url
        }

        val outputFile = createCodegenOutputFile(target)
        codegenOutputFile = outputFile
        val targetFile = resolveTargetOutputFile(selectedLanguage)
        targetOutputFile = targetFile
        preRecordingTargetContent = if (targetFile.exists()) targetFile.readText() else null
        val command = buildCodegenCommand(target, url, outputFile) ?: return false
        val workDir = File(project.basePath ?: ".")
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()

        val handler = OSProcessHandler(process, command.joinToString(" "))
        lastCommand = command.joinToString(" ")
        lastPid = process.pid()
        statusService.setStatus("Playwright: recording")
        logService.info("Recording started (${selectedLanguage.name.lowercase()}).")
        val buffer = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {}

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                buffer.append(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                val raw = readCodegenOutput()
                lastGeneratedCode = raw
                lastSteps = CodegenMapper.extractSteps(selectedLanguage, raw)
                updateOutputFileWithSteps(lastSteps)
                lastGeneratedFile = targetOutputFile
                onStepsUpdated?.invoke(lastSteps, lastGeneratedCode)
                outputBuffer = null
                processHandler = null
                tailerRunning = false
                statusService.setStatus("Playwright: idle")
                logService.info("Recording stopped. ${lastSteps.size} steps captured.")
                handlePendingInsert()
                cleanupOutputFiles()
            }

            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}
        })
        outputBuffer = buffer
        processHandler = handler
        handler.startNotify()
        startOutputTailer()
        return true
    }

    fun stopRecording() {
        processHandler?.destroyProcess()
        processHandler = null
        lastPid = null
        tailerRunning = false
        statusService.setStatus("Playwright: idle")
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(200)
            if (lastSteps.isEmpty()) {
                val raw = readCodegenOutput()
                if (raw.isNotBlank()) {
                    lastGeneratedCode = raw
                    lastSteps = CodegenMapper.extractSteps(selectedLanguage, raw)
                    updateOutputFileWithSteps(lastSteps)
                    lastGeneratedFile = targetOutputFile
                    onStepsUpdated?.invoke(lastSteps, lastGeneratedCode)
                    logService.info("Steps captured after stop: ${lastSteps.size}.")
                }
            }
            handlePendingInsert()
            cleanupOutputFiles()
        }
    }

    fun stopRecordingAndInsert(editor: Editor?) {
        pendingInsertEditor = editor
        pendingOpenAfterStop = editor == null
        stopRecording()
        liveInsertEditor = null
        lastInsertedStepCount = 0
    }

    fun getStepsSnippet(indent: String): String {
        return CodegenMapper.stepsToSnippet(selectedLanguage, lastSteps, indent)
    }

    fun buildNewTestFileContent(projectIsTs: Boolean): String {
        ensurePreferredLanguage()
        val steps = if (lastSteps.isEmpty()) listOf(RecordedStep("// TODO: record actions")) else lastSteps
        return CodegenMapper.defaultTestFile(selectedLanguage, steps)
    }

    fun getSuggestedFileExtension(projectIsTs: Boolean): String {
        ensurePreferredLanguage()
        return CodegenMapper.fileExtension(projectIsTs, selectedLanguage)
    }

    fun setPickedLocator(locator: String) {
        lastPickedLocator = locator
        onLocatorPicked?.invoke(locator)
    }

    fun isPlaywrightAvailable(target: TargetLanguage): Boolean {
        return checkPlaywrightAvailability(target).available
    }

    fun checkPlaywrightAvailability(target: TargetLanguage): PlaywrightCheck {
        val base = project.basePath ?: return PlaywrightCheck(false, "Project path is not available.")
        val root = File(base)
        return when (target) {
            TargetLanguage.JAVASCRIPT -> {
                val nodeOk = runCommand(listOf(NodeLocator.resolveNode(), "--version")) == 0
                if (!nodeOk) return PlaywrightCheck(false, "Node.js not found in PATH.")
                val nodeModules = File(root, "node_modules")
                val hasInstalled = File(nodeModules, "@playwright/test").exists() || File(nodeModules, "playwright").exists()
                val hasBin = NodeLocator.resolvePlaywrightBin(project) != null
                if (hasInstalled || hasBin) return PlaywrightCheck(true, "Playwright JS detected.")
                val pkg = File(root, "package.json")
                if (!pkg.exists()) {
                    return PlaywrightCheck(false, "package.json not found. Initialize npm before installing Playwright.")
                }
                val requireTest = runCommand(listOf(NodeLocator.resolveNode(), "-e", "require('@playwright/test')")) == 0
                val requireCore = runCommand(listOf(NodeLocator.resolveNode(), "-e", "require('playwright')")) == 0
                if (requireTest || requireCore) {
                    return PlaywrightCheck(true, "Playwright JS detected.")
                }
                PlaywrightCheck(false, "Playwright JS is not installed. Run npm install -D @playwright/test.")
            }
            TargetLanguage.PYTHON -> {
                val python = PythonLocator.resolvePythonExecutable(project)
                val pythonOk = runCommand(listOf(python, "--version")) == 0
                if (!pythonOk) return PlaywrightCheck(false, "Python interpreter not found for this project.")
                val importOk = runCommand(listOf(python, "-c", "import playwright")) == 0
                if (importOk) {
                    PlaywrightCheck(true, "Playwright Python detected.")
                } else {
                    PlaywrightCheck(false, "Playwright Python is not installed in the selected environment.")
                }
            }
        }
    }

    fun installPlaywright(target: TargetLanguage, onDone: (Boolean) -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Installing Playwright", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val ok = when (target) {
                    TargetLanguage.JAVASCRIPT -> {
                        val nodeOk = runCommand(listOf(NodeLocator.resolveNode(), "--version")) == 0
                        if (!nodeOk) {
                            logService.error("Node.js not found. Install Node.js and try again.")
                            false
                        } else {
                            val npm = NodeLocator.resolveNpm(project)
                            val npx = NodeLocator.resolveNpx(project)
                            runShellCommand("${quoteArg(npm)} install -D @playwright/test", indicator) &&
                                runShellCommand("${quoteArg(npx)} playwright install", indicator)
                        }
                    }
                    TargetLanguage.PYTHON -> {
                        val python = PythonLocator.resolvePythonExecutable(project)
                        val pythonOk = runCommand(listOf(python, "--version")) == 0
                        if (!pythonOk) {
                            logService.error("Python interpreter not found. Configure your project interpreter and try again.")
                            false
                        } else {
                            runShellCommand("${quoteArg(python)} -m pip install playwright", indicator) &&
                                runShellCommand("${quoteArg(python)} -m playwright install", indicator)
                        }
                    }
                }
                onDone(ok)
            }
        })
    }

    fun startPreview(url: String, locator: String): Boolean {
        if (url.isBlank() || locator.isBlank()) return false
        if (!ensureBridge()) return false
        sendBridgeCommand("HIGHLIGHT", url, locator)
        return true
    }

    fun stopPreview() {
        stopBridge()
    }

    private fun buildCodegenCommand(target: TargetLanguage, url: String?, outputFile: File): List<String>? {
        val cmd = mutableListOf<String>()
        when (target) {
            TargetLanguage.JAVASCRIPT -> {
                val playwrightBin = NodeLocator.resolvePlaywrightBin(project)
                if (playwrightBin != null) {
                    cmd.add(playwrightBin)
                    cmd.add("codegen")
                    cmd.add("--target=javascript")
                } else {
                    cmd.add(NodeLocator.resolveNpx(project))
                    cmd.add("playwright")
                    cmd.add("codegen")
                    cmd.add("--target=javascript")
                }
                cmd.add("--output")
                cmd.add(outputFile.absolutePath)
            }
            TargetLanguage.PYTHON -> {
                cmd.add(PythonLocator.resolvePythonExecutable(project))
                cmd.add("-m")
                cmd.add("playwright")
                cmd.add("codegen")
                cmd.add("--output")
                cmd.add(outputFile.absolutePath)
            }
        }
        if (!url.isNullOrBlank()) {
            cmd.add(url)
        }
        return cmd
    }

    private fun createCodegenOutputFile(target: TargetLanguage): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "playwright-recorder")
        FileUtil.ensureDir(tempDir)
        val ext = if (target == TargetLanguage.PYTHON) ".py" else ".js"
        return Files.createTempFile(tempDir.toPath(), "playwright-recorder-", ext).toFile()
    }

    private fun resolveTargetOutputFile(target: TargetLanguage): File {
        val base = project.basePath ?: "."
        val settings = project.getService(PlaywrightSettingsService::class.java).get()
        val dir = File(base, settings.testsDir.ifBlank { "tests" })
        FileUtil.ensureDir(dir)
        val ext = CodegenMapper.fileExtension(ProjectDetector.isTypeScriptProject(project), target)
        return File(dir, "recorded$ext")
    }

    private fun readCodegenOutput(): String {
        val file = codegenOutputFile ?: return outputBuffer?.toString().orEmpty()
        return if (file.exists()) file.readText() else outputBuffer?.toString().orEmpty()
    }

    private fun handlePendingInsert() {
        val editor = pendingInsertEditor
        if (editor != null) {
            pendingInsertEditor = null
            if (lastSteps.isNotEmpty()) {
                val caret = editor.caretModel.primaryCaret
                val document = editor.document
                val line = document.getLineNumber(caret.offset)
                val indent = CodegenMapper.detectIndent(document, line)
                val snippet = getStepsSnippet(indent)
                if (snippet.isNotBlank()) {
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.insertString(caret.offset, snippet + "\n")
                    }
                }
            }
            return
        }
        if (pendingOpenAfterStop) {
            pendingOpenAfterStop = false
            val file = lastGeneratedFile ?: return
            ApplicationManager.getApplication().invokeLater {
                val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                if (vFile != null) {
                    FileEditorManager.getInstance(project).openFile(vFile, true)
                }
            }
        }
    }

    private fun updateOutputFileWithSteps(steps: List<RecordedStep>) {
        val file = targetOutputFile ?: return
        val existing = preRecordingTargetContent ?: if (file.exists()) file.readText() else ""
        val updated = CodegenMapper.mergeStepsIntoFile(selectedLanguage, existing, steps) ?: return
        file.writeText(updated)
    }

    private fun cleanupOutputFiles() {
        preRecordingTargetContent = null
        codegenOutputFile?.delete()
        codegenOutputFile = null
    }

    private fun startOutputTailer() {
        if (tailerRunning) return
        tailerRunning = true
        ApplicationManager.getApplication().executeOnPooledThread {
            var lastContent = ""
            while (tailerRunning && processHandler != null) {
                val raw = readCodegenOutput()
                if (raw.isNotBlank() && raw != lastContent) {
                    lastContent = raw
                    lastGeneratedCode = raw
                    val steps = CodegenMapper.extractSteps(selectedLanguage, raw)
                    lastSteps = steps
                    onStepsUpdated?.invoke(lastSteps, lastGeneratedCode)
                    maybeLiveInsert(steps)
                }
                Thread.sleep(500)
            }
        }
    }

    private fun maybeLiveInsert(steps: List<RecordedStep>) {
        val editor = liveInsertEditor ?: return
        if (steps.size <= lastInsertedStepCount) return
        val newSteps = steps.subList(lastInsertedStepCount, steps.size)
        val snippet = CodegenMapper.stepsToSnippet(selectedLanguage, newSteps, liveInsertIndent)
        if (snippet.isBlank()) return
        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            document.insertString(liveInsertOffset, snippet + "\n")
            liveInsertOffset += snippet.length + 1
            editor.caretModel.moveToOffset(liveInsertOffset)
        }
        lastInsertedStepCount = steps.size
    }

    private fun runShellCommand(command: String, indicator: ProgressIndicator): Boolean {
        val base = project.basePath ?: "."
        val workDir = File(base)
        val (shell, flag) = if (isWindows()) "cmd.exe" to "/c" else "sh" to "-c"
        val process = ProcessBuilder(shell, flag, command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val handler = OSProcessHandler(process, command)
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {}

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                indicator.text2 = event.text.trim()
            }

            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}

            override fun processTerminated(event: ProcessEvent) {}
        })
        handler.startNotify()
        handler.waitFor()
        return process.exitValue() == 0
    }

    private fun runCommand(command: List<String>, timeoutMs: Long = 5000): Int? {
        return try {
            val workDir = File(project.basePath ?: ".")
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return null
            }
            process.exitValue()
        } catch (_: Exception) {
            null
        }
    }

    private fun quoteArg(value: String): String {
        return if (value.contains(" ")) "\"$value\"" else value
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase().contains("win")
    }

    fun startSession(url: String?): Boolean {
        if (!ensureBridge()) return false
        if (!url.isNullOrBlank()) {
            sendBridgeCommand("START", url)
        }
        return true
    }

    fun beginPickLocator(url: String?): Boolean {
        if (!ensureBridge()) return false
        if (!url.isNullOrBlank()) {
            sendBridgeCommand("START", url)
        }
        sendBridgeCommand("PICK", url ?: "")
        logService.info("Locator picker started.")
        return true
    }

    fun resetContext(): Boolean {
        if (!ensureBridge()) return false
        sendBridgeCommand("RESET")
        logService.info("Browser context reset.")
        return true
    }

    private fun ensureBridge(): Boolean {
        if (bridgeHandler != null) return true
        ensurePreferredLanguage()
        val script = ensureBridgeScript(selectedLanguage)
        val command = mutableListOf<String>()
        when (selectedLanguage) {
            TargetLanguage.JAVASCRIPT -> {
                command.add(NodeLocator.resolveNode())
                command.add(script.absolutePath)
            }
            TargetLanguage.PYTHON -> {
                command.add(PythonLocator.resolvePythonExecutable(project))
                command.add(script.absolutePath)
            }
        }

        val workDir = File(project.basePath ?: ".")
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val handler = OSProcessHandler(process, command.joinToString(" "))
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {}

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                handleBridgeOutput(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                bridgeHandler = null
                bridgeInput = null
            }

            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}
        })
        bridgeHandler = handler
        bridgeInput = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        handler.startNotify()
        return true
    }

    private fun sendBridgeCommand(command: String, vararg args: String) {
        val writer = bridgeInput ?: return
        val encodedArgs = args.map { encode(it) }
        val line = buildString {
            append(command)
            if (encodedArgs.isNotEmpty()) {
                append(" ")
                append(encodedArgs.joinToString(" "))
            }
        }
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    private fun stopBridge() {
        if (bridgeHandler == null) return
        sendBridgeCommand("STOP")
        bridgeHandler?.destroyProcess()
        bridgeHandler = null
        bridgeInput = null
    }

    private fun handleBridgeOutput(text: String) {
        bridgeBuffer.append(text)
        while (true) {
            val idx = bridgeBuffer.indexOf("\n")
            if (idx < 0) break
            val line = bridgeBuffer.substring(0, idx).trim()
            bridgeBuffer.delete(0, idx + 1)
            handleBridgeLine(line)
        }
    }

    private fun handleBridgeLine(line: String) {
        if (!line.startsWith("PWRECORDER:")) return
        when {
            line.startsWith("PWRECORDER:LOCATOR:") -> {
                val b64 = line.removePrefix("PWRECORDER:LOCATOR:")
                if (b64.isNotBlank()) {
                    val locator = decode(b64)
                    setPickedLocator(locator)
                    logService.info("Locator captured.")
                }
            }
            line.startsWith("PWRECORDER:ERROR:") -> {
                val b64 = line.removePrefix("PWRECORDER:ERROR:")
                val message = if (b64.isNotBlank()) decode(b64) else "Unknown error"
                logService.error(message)
            }
        }
    }

    private fun ensureBridgeScript(target: TargetLanguage): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "playwright-recorder")
        if (!tempDir.exists()) tempDir.mkdirs()
        return when (target) {
            TargetLanguage.JAVASCRIPT -> {
                val file = File(tempDir, "bridge.js")
                val content = """
                    const { chromium } = require('playwright');
                    const readline = require('readline');

                    const rl = readline.createInterface({ input: process.stdin, terminal: false });
                    let browser = null;
                    let context = null;
                    let page = null;
                    let bindingInstalled = false;

                    function encode(value) { return Buffer.from(value, 'utf8').toString('base64'); }
                    function decode(value) { return Buffer.from(value, 'base64').toString('utf8'); }
                    function send(line) { process.stdout.write(line + '\\n'); }

                    async function ensurePage(url) {
                      if (!browser) {
                        browser = await chromium.launch({ headless: false });
                        context = await browser.newContext();
                        page = await context.newPage();
                      }
                      if (!bindingInstalled) {
                        await page.exposeBinding('pw_recorder_pick', (_, locator) => {
                          send('PWRECORDER:LOCATOR:' + encode(locator));
                        });
                        bindingInstalled = true;
                      }
                      if (url) {
                        await page.goto(url);
                      }
                      return page;
                    }

                    function buildLocator(el) {
                      if (!el || !el.tagName) return { locator: '', type: 'css' };
                      const testId = el.getAttribute('data-testid');
                      if (testId) return { locator: 'data-testid=' + testId, type: 'testid' };
                      const dataTest = el.getAttribute('data-test');
                      if (dataTest) return { locator: 'css=[data-test=\"' + CSS.escape(dataTest) + '\"]', type: 'css' };
                      const dataQa = el.getAttribute('data-qa');
                      if (dataQa) return { locator: 'css=[data-qa=\"' + CSS.escape(dataQa) + '\"]', type: 'css' };

                      function escapeText(value) {
                        return value.replace(/\\/g, '\\\\').replace(/\"/g, '\\\"');
                      }

                      function getAriaLabelledByText(node) {
                        const ids = (node.getAttribute('aria-labelledby') || '').split(/\\s+/).filter(Boolean);
                        if (!ids.length) return '';
                        return ids.map(id => {
                          const el = document.getElementById(id);
                          return el && el.innerText ? el.innerText.trim() : '';
                        }).filter(Boolean).join(' ');
                      }

                      function getLabelText(node) {
                        const id = node.getAttribute('id');
                        if (id) {
                          const label = document.querySelector('label[for=\"' + CSS.escape(id) + '\"]');
                          if (label && label.innerText) return label.innerText.trim();
                        }
                        if (node.closest) {
                          const parentLabel = node.closest('label');
                          if (parentLabel && parentLabel.innerText) return parentLabel.innerText.trim();
                        }
                        return '';
                      }

                      function inferRole(node) {
                        const explicit = node.getAttribute('role');
                        if (explicit) return explicit;
                        const tag = node.tagName.toLowerCase();
                        if (tag === 'button') return 'button';
                        if (tag === 'a' && node.getAttribute('href')) return 'link';
                        if (tag === 'input') {
                          const type = (node.getAttribute('type') || '').toLowerCase();
                          if (type === 'submit' || type === 'button' || type === 'reset') return 'button';
                          if (type === 'checkbox') return 'checkbox';
                          if (type === 'radio') return 'radio';
                          if (type === 'range') return 'slider';
                          return 'textbox';
                        }
                        if (tag === 'select') return 'combobox';
                        if (tag === 'textarea') return 'textbox';
                        return '';
                      }

                      const ariaLabel = el.getAttribute('aria-label') || '';
                      const labelledBy = getAriaLabelledByText(el);
                      const labelText = getLabelText(el);
                      const placeholder = el.getAttribute('placeholder') || '';
                      const visibleText = el.innerText ? el.innerText.trim() : '';
                      const role = inferRole(el);
                      const name = (ariaLabel || labelledBy || labelText || placeholder || visibleText).trim();
                      if (role && name) {
                        return { locator: 'role=' + role + '[name=\"' + escapeText(name) + '\"]', type: 'role' };
                      }
                      if (visibleText && visibleText.length <= 60) {
                        return { locator: 'text=\"' + escapeText(visibleText) + '\"', type: 'text' };
                      }
                      if (el.id) return { locator: 'css=#' + CSS.escape(el.id), type: 'css' };
                      const parts = [];
                      let node = el;
                      while (node && node.nodeType === 1 && node.tagName.toLowerCase() !== 'html') {
                        let selector = node.tagName.toLowerCase();
                        const parent = node.parentElement;
                        if (parent) {
                          const siblings = Array.from(parent.children).filter(n => n.tagName === node.tagName);
                          if (siblings.length > 1) {
                            selector += ':nth-of-type(' + (siblings.indexOf(node) + 1) + ')';
                          }
                        }
                        parts.unshift(selector);
                        node = parent;
                      }
                      return { locator: 'css=' + parts.join(' > '), type: 'css' };
                    }

                    async function enablePicker() {
                      if (!page) return;
                      await page.evaluate(() => {
                        if (window.__pwRecorderPickActive) return;
                        window.__pwRecorderPickActive = true;
                        window.__pwRecorderLast = null;

                        function clearOutline() {
                          if (window.__pwRecorderLast) {
                            window.__pwRecorderLast.style.outline = '';
                            window.__pwRecorderLast.style.outlineOffset = '';
                          }
                        }

                        function hoverHandler(e) {
                          if (!e.target || !e.target.style) return;
                          if (window.__pwRecorderLast === e.target) return;
                          clearOutline();
                          window.__pwRecorderLast = e.target;
                          e.target.style.outline = '2px solid #ff0055';
                          e.target.style.outlineOffset = '2px';
                        }

                          function clickHandler(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            const target = e.target;
                            const result = buildLocator(target);
                            if (window.pw_recorder_pick) {
                              window.pw_recorder_pick(result.locator);
                            }
                            cleanup();
                        }

                        function cleanup() {
                          document.removeEventListener('mousemove', hoverHandler, true);
                          document.removeEventListener('click', clickHandler, true);
                          clearOutline();
                          window.__pwRecorderPickActive = false;
                        }

                        document.addEventListener('mousemove', hoverHandler, true);
                        document.addEventListener('click', clickHandler, true);
                      });
                    }

                    function locatorToSelector(locator) {
                      if (locator.startsWith('role=')) {
                        const m = locator.match(/^role=([\\w-]+)\\[name=\"(.+)\"\\]$/);
                        if (m) return { type: 'role', role: m[1], name: m[2] };
                      }
                      if (locator.startsWith('text=\"')) {
                        const m = locator.match(/^text=\"(.+)\"$/);
                        if (m) return { type: 'text', text: m[1] };
                      }
                      if (locator.startsWith('data-testid=')) {
                        const m = locator.match(/^data-testid=(.+)$/);
                        if (m) return { type: 'testid', id: m[1] };
                      }
                      return { type: 'css', css: locator.replace(/^css=/, '') };
                    }

                    async function highlight(locator) {
                      if (!page || !locator) return;
                      const parsed = locatorToSelector(locator);
                      const loc = parsed.type === 'role'
                        ? page.getByRole(parsed.role, { name: parsed.name })
                        : parsed.type === 'text'
                          ? page.getByText(parsed.text)
                          : parsed.type === 'testid'
                            ? page.getByTestId(parsed.id)
                            : page.locator(parsed.css || locator);
                      await loc.first().scrollIntoViewIfNeeded();
                      await loc.first().evaluate(el => {
                        el.style.outline = '2px solid #ff0055';
                        el.style.outlineOffset = '2px';
                      });
                    }

                    async function handle(line) {
                      const parts = line.trim().split(' ');
                      const cmd = parts[0];
                      const url = parts[1] ? decode(parts[1]) : '';
                      const locator = parts[2] ? decode(parts[2]) : '';

                      if (cmd === 'START') {
                        await ensurePage(url);
                        return;
                      }
                      if (cmd === 'PICK') {
                        await ensurePage(url);
                        await enablePicker();
                        return;
                      }
                      if (cmd === 'HIGHLIGHT') {
                        await ensurePage(url);
                        await highlight(locator);
                        return;
                      }
                      if (cmd === 'RESET') {
                        if (context) await context.close();
                        context = await browser.newContext();
                        page = await context.newPage();
                        bindingInstalled = false;
                        return;
                      }
                      if (cmd === 'STOP') {
                        if (browser) await browser.close();
                        process.exit(0);
                      }
                    }

                    rl.on('line', async (line) => {
                      try {
                        await handle(line);
                      } catch (e) {
                        send('PWRECORDER:ERROR:' + encode(String(e)));
                      }
                    });
                """.trimIndent()
                Files.write(file.toPath(), content.toByteArray(StandardCharsets.UTF_8))
                file
            }
            TargetLanguage.PYTHON -> {
                val file = File(tempDir, "bridge.py")
                val content = """
                    import base64
                    import sys
                    from playwright.sync_api import sync_playwright

                    browser = None
                    context = None
                    page = None
                    binding_installed = False

                    def encode(value: str) -> str:
                        return base64.b64encode(value.encode("utf-8")).decode("utf-8")

                    def decode(value: str) -> str:
                        return base64.b64decode(value.encode("utf-8")).decode("utf-8")

                    def send(line: str):
                        sys.stdout.write(line + "\\n")
                        sys.stdout.flush()

                    def ensure_page(p, url: str):
                        global browser, context, page, binding_installed
                        if browser is None:
                            browser = p.chromium.launch(headless=False)
                            context = browser.new_context()
                            page = context.new_page()
                        if not binding_installed:
                            def picked(source, locator):
                                send("PWRECORDER:LOCATOR:" + encode(locator))
                            page.expose_binding("pw_recorder_pick", picked)
                            binding_installed = True
                        if url:
                            page.goto(url)
                        return page

                    def enable_picker(page):
                        page.evaluate(\"\"\"\n                        () => {\n                          if (window.__pwRecorderPickActive) return;\n                          window.__pwRecorderPickActive = true;\n                          window.__pwRecorderLast = null;\n\n                          function clearOutline() {\n                            if (window.__pwRecorderLast) {\n                              window.__pwRecorderLast.style.outline = '';\n                              window.__pwRecorderLast.style.outlineOffset = '';\n                            }\n                          }\n\n                          function buildLocator(el) {\n                            if (!el || !el.tagName) return { locator: '', type: 'css' };\n                            const testId = el.getAttribute('data-testid');\n                            if (testId) return { locator: 'data-testid=' + testId, type: 'testid' };\n                            const dataTest = el.getAttribute('data-test');\n                            if (dataTest) return { locator: 'css=[data-test=\"' + CSS.escape(dataTest) + '\"]', type: 'css' };\n                            const dataQa = el.getAttribute('data-qa');\n                            if (dataQa) return { locator: 'css=[data-qa=\"' + CSS.escape(dataQa) + '\"]', type: 'css' };\n\n                            function escapeText(value) {\n                              return value.replace(/\\\\/g, '\\\\\\\\').replace(/\"/g, '\\\"');\n                            }\n\n                            function getAriaLabelledByText(node) {\n                              const ids = (node.getAttribute('aria-labelledby') || '').split(/\\s+/).filter(Boolean);\n                              if (!ids.length) return '';\n                              return ids.map(id => {\n                                const el = document.getElementById(id);\n                                return el && el.innerText ? el.innerText.trim() : '';\n                              }).filter(Boolean).join(' ');\n                            }\n\n                            function getLabelText(node) {\n                              const id = node.getAttribute('id');\n                              if (id) {\n                                const label = document.querySelector('label[for=\"' + CSS.escape(id) + '\"]');\n                                if (label && label.innerText) return label.innerText.trim();\n                              }\n                              if (node.closest) {\n                                const parentLabel = node.closest('label');\n                                if (parentLabel && parentLabel.innerText) return parentLabel.innerText.trim();\n                              }\n                              return '';\n                            }\n\n                            function inferRole(node) {\n                              const explicit = node.getAttribute('role');\n                              if (explicit) return explicit;\n                              const tag = node.tagName.toLowerCase();\n                              if (tag === 'button') return 'button';\n                              if (tag === 'a' && node.getAttribute('href')) return 'link';\n                              if (tag === 'input') {\n                                const type = (node.getAttribute('type') || '').toLowerCase();\n                                if (type === 'submit' || type === 'button' || type === 'reset') return 'button';\n                                if (type === 'checkbox') return 'checkbox';\n                                if (type === 'radio') return 'radio';\n                                if (type === 'range') return 'slider';\n                                return 'textbox';\n                              }\n                              if (tag === 'select') return 'combobox';\n                              if (tag === 'textarea') return 'textbox';\n                              return '';\n                            }\n\n                            const ariaLabel = el.getAttribute('aria-label') || '';\n                            const labelledBy = getAriaLabelledByText(el);\n                            const labelText = getLabelText(el);\n                            const placeholder = el.getAttribute('placeholder') || '';\n                            const visibleText = el.innerText ? el.innerText.trim() : '';\n                            const role = inferRole(el);\n                            const name = (ariaLabel || labelledBy || labelText || placeholder || visibleText).trim();\n                            if (role && name) {\n                              return { locator: 'role=' + role + '[name=\"' + escapeText(name) + '\"]', type: 'role' };\n                            }\n                            if (visibleText && visibleText.length <= 60) {\n                              return { locator: 'text=\"' + escapeText(visibleText) + '\"', type: 'text' };\n                            }\n                            if (el.id) return { locator: 'css=#' + CSS.escape(el.id), type: 'css' };\n                            const parts = [];\n                            let node = el;\n                            while (node && node.nodeType === 1 && node.tagName.toLowerCase() !== 'html') {\n                              let selector = node.tagName.toLowerCase();\n                              const parent = node.parentElement;\n                              if (parent) {\n                                const siblings = Array.from(parent.children).filter(n => n.tagName === node.tagName);\n                                if (siblings.length > 1) {\n                                  selector += ':nth-of-type(' + (siblings.indexOf(node) + 1) + ')';\n                                }\n                              }\n                              parts.unshift(selector);\n                              node = parent;\n                            }\n                            return { locator: 'css=' + parts.join(' > '), type: 'css' };\n                          }\n\n                          function hoverHandler(e) {\n                            if (!e.target || !e.target.style) return;\n                            if (window.__pwRecorderLast === e.target) return;\n                            clearOutline();\n                            window.__pwRecorderLast = e.target;\n                            e.target.style.outline = '2px solid #ff0055';\n                            e.target.style.outlineOffset = '2px';\n                          }\n\n                          function clickHandler(e) {\n                            e.preventDefault();\n                            e.stopPropagation();\n                            const target = e.target;\n                            const result = buildLocator(target);\n                            if (window.pw_recorder_pick) {\n                              window.pw_recorder_pick(result.locator);\n                            }\n                            cleanup();\n                          }\n\n                          function cleanup() {\n                            document.removeEventListener('mousemove', hoverHandler, true);\n                            document.removeEventListener('click', clickHandler, true);\n                            clearOutline();\n                            window.__pwRecorderPickActive = false;\n                          }\n\n                          document.addEventListener('mousemove', hoverHandler, true);\n                          document.addEventListener('click', clickHandler, true);\n                        }\n                        \"\"\")

                    def highlight(page, locator):
                        if locator.startswith("role=") and "[name=\"" in locator:
                            role = locator.split("[", 1)[0].replace("role=", "")
                            name = locator.split("[name=\"", 1)[1].rsplit("\"]", 1)[0]
                            loc = page.get_by_role(role, name=name)
                        elif locator.startswith("text=\""):
                            text = locator.replace("text=\"", "").rstrip("\"")
                            loc = page.get_by_text(text)
                        elif locator.startswith("data-testid="):
                            testid = locator.replace("data-testid=", "")
                            loc = page.get_by_test_id(testid)
                        elif locator.startswith("css="):
                            loc = page.locator(locator.replace("css=", ""))
                        else:
                            loc = page.locator(locator)
                        loc.first.scroll_into_view_if_needed()
                        loc.first.evaluate(\"el => { el.style.outline = '2px solid #ff0055'; el.style.outlineOffset = '2px'; }\")

                    def handle(p, line: str):
                        parts = line.strip().split(\" \")
                        cmd = parts[0]
                        url = decode(parts[1]) if len(parts) > 1 and parts[1] else \"\"
                        locator = decode(parts[2]) if len(parts) > 2 and parts[2] else \"\"
                        if cmd == \"START\":
                            ensure_page(p, url)
                        elif cmd == \"PICK\":
                            ensure_page(p, url)
                            enable_picker(page)
                        elif cmd == \"HIGHLIGHT\":
                            ensure_page(p, url)
                            highlight(page, locator)
                        elif cmd == \"RESET\":
                            if context:
                                context.close()
                            context = browser.new_context()
                            page = context.new_page()
                            binding_installed = False
                        elif cmd == \"STOP\":
                            if browser:
                                browser.close()
                            sys.exit(0)

                    def main():
                        with sync_playwright() as p:
                            for raw in sys.stdin:
                                if not raw:
                                    continue
                                try:
                                    handle(p, raw)
                                except Exception as exc:
                                    send(\"PWRECORDER:ERROR:\" + encode(str(exc)))

                    if __name__ == \"__main__\":
                        main()
                """.trimIndent()
                Files.write(file.toPath(), content.toByteArray(StandardCharsets.UTF_8))
                file
            }
        }
    }

    private fun encode(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decode(value: String): String {
        return String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    }

    fun showTraceViewer(traceFile: File): Boolean {
        if (!traceFile.exists()) return false
        ensurePreferredLanguage()
        val command = mutableListOf<String>()
        when (selectedLanguage) {
            TargetLanguage.JAVASCRIPT -> {
                val playwrightBin = NodeLocator.resolvePlaywrightBin(project)
                if (playwrightBin != null) {
                    command.add(playwrightBin)
                    command.add("show-trace")
                } else {
                    command.add(NodeLocator.resolveNpx(project))
                    command.add("playwright")
                    command.add("show-trace")
                }
            }
            TargetLanguage.PYTHON -> {
                command.add(PythonLocator.resolvePythonExecutable(project))
                command.add("-m")
                command.add("playwright")
                command.add("show-trace")
            }
        }
        command.add(traceFile.absolutePath)
        val workDir = File(project.basePath ?: ".")
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val handler = OSProcessHandler(process, command.joinToString(" "))
        handler.startNotify()
        return true
    }
}
