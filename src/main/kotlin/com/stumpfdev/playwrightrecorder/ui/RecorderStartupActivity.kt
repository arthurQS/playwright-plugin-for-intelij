package com.stumpfdev.playwrightrecorder.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.stumpfdev.playwrightrecorder.service.EditorLocatorHighlighter

class RecorderStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        project.getService(EditorLocatorHighlighter::class.java)
    }
}
