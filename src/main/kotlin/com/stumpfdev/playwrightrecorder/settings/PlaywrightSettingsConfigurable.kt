package com.stumpfdev.playwrightrecorder.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JLabel

class PlaywrightSettingsConfigurable(private val project: Project) : Configurable {
    private var panel: JPanel? = null
    private val urlField = JBTextField()
    private val testsDirField = JBTextField()
    private val targetBox = ComboBox(PreferredTarget.values())

    override fun getDisplayName(): String = "Playwright Recorder"

    override fun createComponent(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JLabel("Default URL:"), urlField, 1, false)
            .addLabeledComponent(JLabel("Tests directory:"), testsDirField, 1, false)
            .addLabeledComponent(JLabel("Preferred target:"), targetBox, 1, false)
            .panel
        panel = JPanel(BorderLayout())
        panel?.add(form, BorderLayout.NORTH)
        return panel as JPanel
    }

    override fun isModified(): Boolean {
        val state = project.getService(PlaywrightSettingsService::class.java).get()
        return urlField.text != state.defaultUrl ||
            testsDirField.text != state.testsDir ||
            (targetBox.selectedItem as PreferredTarget) != state.preferredTarget
    }

    override fun apply() {
        val service = project.getService(PlaywrightSettingsService::class.java)
        val state = service.get()
        state.defaultUrl = urlField.text.trim()
        state.testsDir = testsDirField.text.trim().ifBlank { "tests" }
        state.preferredTarget = targetBox.selectedItem as PreferredTarget
    }

    override fun reset() {
        val state = project.getService(PlaywrightSettingsService::class.java).get()
        urlField.text = state.defaultUrl
        testsDirField.text = state.testsDir
        targetBox.selectedItem = state.preferredTarget
    }

    override fun disposeUIResources() {
        panel = null
    }
}
