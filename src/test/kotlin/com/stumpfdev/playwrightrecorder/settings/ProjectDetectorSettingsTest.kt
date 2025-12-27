package com.stumpfdev.playwrightrecorder.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.stumpfdev.playwrightrecorder.util.ProjectDetector
import com.stumpfdev.playwrightrecorder.util.TargetLanguage

class ProjectDetectorSettingsTest : BasePlatformTestCase() {
    fun testPreferredTargetOverride() {
        val service = project.getService(PlaywrightSettingsService::class.java)
        val state = service.get()
        state.preferredTarget = PreferredTarget.PYTHON

        val detected = ProjectDetector.detect(project)
        assertEquals(TargetLanguage.PYTHON, detected.preferred)
    }

    fun testAutoTargetDefault() {
        val service = project.getService(PlaywrightSettingsService::class.java)
        val state = service.get()
        state.preferredTarget = PreferredTarget.AUTO

        val detected = ProjectDetector.detect(project)
        assertEquals(TargetLanguage.JAVASCRIPT, detected.preferred)
    }
}
