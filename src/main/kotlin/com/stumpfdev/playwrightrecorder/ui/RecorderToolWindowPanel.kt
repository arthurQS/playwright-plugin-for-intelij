package com.stumpfdev.playwrightrecorder.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.stumpfdev.playwrightrecorder.model.RecordedStep
import com.stumpfdev.playwrightrecorder.service.RecorderService
import com.stumpfdev.playwrightrecorder.ui.RecorderLogService
import com.stumpfdev.playwrightrecorder.settings.PlaywrightSettingsService
import com.stumpfdev.playwrightrecorder.util.ProjectDetector
import com.stumpfdev.playwrightrecorder.settings.PreferredTarget
import com.stumpfdev.playwrightrecorder.util.TargetLanguage
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

class RecorderToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val service = project.getService(RecorderService::class.java)
    private val logService = project.getService(RecorderLogService::class.java)
    private val stepsList = JBList<RecordedStep>()
    private val rawOutput = JBTextArea()
    private val logArea = JBTextArea()
    private val targetSelector = ComboBox(PreferredTarget.values())
    private val urlField = JBTextField()
    private val locatorField = JBTextField()
    private val statusLabel = JLabel("Status: idle")
    private val availabilityLabel = JLabel("Playwright: checking...")
    private val detectedLabel = JLabel("Detected: -")
    private val installButton = JButton("Install")
    private val refreshButton = JButton("Refresh")
    private var previewActive = false
    private val highlightButton = JButton("Highlight")
    private val stopPreviewButton = JButton("Stop Preview")
    private val statusTimer = Timer(1000) { updateStatusLabel() }

    init {
        border = JBUI.Borders.empty(8)
        add(createToolbar(), BorderLayout.NORTH)
        add(createCenter(), BorderLayout.CENTER)
        refreshTargets()
        service.onStepsUpdated = { steps, raw -> updateSteps(steps, raw) }
        service.onLocatorPicked = { locator -> locatorField.text = locator }
        logService.onLogUpdated = { logs -> updateLogs(logs) }
        targetSelector.addActionListener {
            val selected = targetSelector.selectedItem as? PreferredTarget ?: return@addActionListener
            val settings = project.getService(PlaywrightSettingsService::class.java)
            settings.get().preferredTarget = selected
            val detected = ProjectDetector.detect(project)
            val resolved = when (selected) {
                PreferredTarget.AUTO -> detected.preferred
                PreferredTarget.JAVASCRIPT -> TargetLanguage.JAVASCRIPT
                PreferredTarget.PYTHON -> TargetLanguage.PYTHON
            }
            service.setSelectedLanguage(resolved)
            updateDetectedLabel(detected)
            refreshPlaywrightStatus()
        }
        urlField.text = service.lastStartUrl
        locatorField.text = service.lastPickedLocator
        urlField.toolTipText = "Optional: start recording at this URL"
        locatorField.toolTipText = "Locator to highlight in the browser"
        if (urlField.text.isBlank()) {
            val settings = project.getService(PlaywrightSettingsService::class.java).get()
            urlField.text = settings.defaultUrl
            if (settings.defaultUrl.isNotBlank()) {
                service.setStartUrl(settings.defaultUrl)
            }
        }
        urlField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                service.setStartUrl(urlField.text.trim())
            }
        })
        locatorField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updatePreviewButtons()
            }
        })
        urlField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updatePreviewButtons()
            }
        })
        installButton.addActionListener { installPlaywright() }
        refreshButton.addActionListener { refreshPlaywrightStatus() }
        updatePreviewButtons()
        refreshPlaywrightStatus()
        statusTimer.start()
    }

    private fun createToolbar(): JPanel {
        val group = ActionManager.getInstance().getAction("PlaywrightRecorder.Toolbar") as DefaultActionGroup
        val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar("PlaywrightRecorder.Toolbar", group, true)
        toolbar.targetComponent = this

        val panel = JPanel(BorderLayout())
        panel.add(toolbar.component, BorderLayout.CENTER)

        val right = JPanel(BorderLayout())
        right.add(JLabel("Target:"), BorderLayout.WEST)
        right.add(targetSelector, BorderLayout.CENTER)
        panel.add(right, BorderLayout.EAST)
        return panel
    }

    private fun createCenter(): JPanel {
        val panel = JPanel(BorderLayout())
        stepsList.emptyText.text = "No recorded steps yet"
        val scroll = JBScrollPane(stepsList)
        scroll.preferredSize = Dimension(400, 200)
        panel.add(scroll, BorderLayout.CENTER)

        val south = JPanel(BorderLayout())
        val controls = JPanel(BorderLayout())
        controls.add(createStatusRow(), BorderLayout.NORTH)
        controls.add(createPreviewControls(), BorderLayout.SOUTH)
        south.add(controls, BorderLayout.NORTH)

        rawOutput.isEditable = false
        rawOutput.emptyText.text = "Raw recorder output"
        val rawScroll = JBScrollPane(rawOutput)
        rawScroll.preferredSize = Dimension(400, 140)

        logArea.isEditable = false
        logArea.emptyText.text = "Recorder log"
        val logScroll = JBScrollPane(logArea)
        logScroll.preferredSize = Dimension(400, 100)

        val bottom = JPanel(BorderLayout())
        bottom.add(rawScroll, BorderLayout.CENTER)
        bottom.add(logScroll, BorderLayout.SOUTH)
        south.add(bottom, BorderLayout.CENTER)

        panel.add(south, BorderLayout.SOUTH)
        return panel
    }

    private fun createStatusRow(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        panel.border = JBUI.Borders.empty(0, 0, 6, 0)
        panel.add(statusLabel)
        panel.add(JLabel("|"))
        panel.add(availabilityLabel)
        panel.add(detectedLabel)
        panel.add(installButton)
        panel.add(refreshButton)
        return panel
    }

    private fun createPreviewControls(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(6, 0, 6, 0)

        val fields = JPanel(BorderLayout())
        fields.add(JLabel("URL:"), BorderLayout.WEST)
        fields.add(urlField, BorderLayout.CENTER)
        panel.add(fields, BorderLayout.NORTH)

        val locatorRow = JPanel(BorderLayout())
        locatorRow.add(JLabel("Locator:"), BorderLayout.WEST)
        locatorRow.add(locatorField, BorderLayout.CENTER)
        panel.add(locatorRow, BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        highlightButton.toolTipText = "Highlight the locator in the Playwright browser"
        stopPreviewButton.toolTipText = "Stop the preview browser"
        highlightButton.addActionListener {
            val url = urlField.text.trim()
            val locator = locatorField.text.trim()
            val ok = service.startPreview(url, locator)
            if (!ok) {
                Messages.showWarningDialog(project, "Provide a valid URL and locator to preview.", "Playwright Recorder")
                previewActive = false
                updatePreviewButtons()
                return@addActionListener
            }
            previewActive = true
            updatePreviewButtons()
        }
        stopPreviewButton.addActionListener {
            service.stopPreview()
            previewActive = false
            updatePreviewButtons()
        }
        buttons.add(highlightButton)
        buttons.add(stopPreviewButton)
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    fun refreshTargets() {
        val detected = ProjectDetector.detect(project)
        val settings = project.getService(PlaywrightSettingsService::class.java).get()
        targetSelector.selectedItem = settings.preferredTarget
        val preferred = when (settings.preferredTarget) {
            PreferredTarget.AUTO -> detected.preferred
            PreferredTarget.JAVASCRIPT -> TargetLanguage.JAVASCRIPT
            PreferredTarget.PYTHON -> TargetLanguage.PYTHON
        }
        service.setSelectedLanguage(preferred)
        updateDetectedLabel(detected)
    }

    fun updateSteps(steps: List<RecordedStep>, raw: String) {
        stepsList.setListData(steps.toTypedArray())
        rawOutput.text = raw
        updateStatusLabel()
    }

    private fun updateLogs(logs: List<String>) {
        logArea.text = logs.joinToString("\n")
        logArea.caretPosition = logArea.document.length
    }

    private fun updateStatusLabel() {
        val text = if (service.isRecording()) "Status: recording" else "Status: idle"
        statusLabel.text = text
    }

    private fun refreshPlaywrightStatus() {
        val detected = ProjectDetector.detect(project)
        val selection = targetSelector.selectedItem as? PreferredTarget ?: PreferredTarget.AUTO
        val target = when (selection) {
            PreferredTarget.AUTO -> detected.preferred
            PreferredTarget.JAVASCRIPT -> TargetLanguage.JAVASCRIPT
            PreferredTarget.PYTHON -> TargetLanguage.PYTHON
        }
        val check = service.checkPlaywrightAvailability(target)
        availabilityLabel.text = "Playwright: ${check.message}"
        if (check.available) {
            availabilityLabel.foreground = JBColor(0x2E7D32, 0x6FBF73)
            installButton.isEnabled = false
        } else {
            availabilityLabel.foreground = JBColor(0xC62828, 0xFF8A80)
            installButton.isEnabled = true
        }
    }

    private fun updateDetectedLabel(detected: com.stumpfdev.playwrightrecorder.util.DetectedTargets) {
        val text = if (detected.hasJavaScript && detected.hasPython) {
            "Detected: JS & Python"
        } else if (detected.hasPython) {
            "Detected: Python"
        } else if (detected.hasJavaScript) {
            "Detected: JavaScript"
        } else {
            "Detected: none"
        }
        detectedLabel.text = text
    }

    private fun installPlaywright() {
        installButton.isEnabled = false
        refreshButton.isEnabled = false
        availabilityLabel.text = "Playwright: installing..."
        availabilityLabel.foreground = JBColor(0x1565C0, 0x90CAF9)
        val target = targetSelector.selectedItem as? TargetLanguage ?: TargetLanguage.JAVASCRIPT
        service.installPlaywright(target) { ok ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                refreshButton.isEnabled = true
                if (!ok) {
                    availabilityLabel.text = "Playwright: installation failed."
                    availabilityLabel.foreground = JBColor(0xC62828, 0xFF8A80)
                    installButton.isEnabled = true
                    return@invokeLater
                }
                refreshPlaywrightStatus()
            }
        }
    }

    private fun updatePreviewButtons() {
        val urlOk = urlField.text.trim().isNotEmpty()
        val locatorOk = locatorField.text.trim().isNotEmpty()
        highlightButton.isEnabled = urlOk && locatorOk
        stopPreviewButton.isEnabled = previewActive
    }
}
