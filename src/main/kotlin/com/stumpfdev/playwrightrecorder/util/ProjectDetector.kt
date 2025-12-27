package com.stumpfdev.playwrightrecorder.util

import com.intellij.openapi.project.Project
import com.stumpfdev.playwrightrecorder.settings.PlaywrightSettingsService
import com.stumpfdev.playwrightrecorder.settings.PreferredTarget
import java.io.File

enum class TargetLanguage {
    JAVASCRIPT,
    PYTHON
}

data class DetectedTargets(
    val hasJavaScript: Boolean,
    val hasPython: Boolean,
    val preferred: TargetLanguage
)

object ProjectDetector {
    fun detect(project: Project): DetectedTargets {
        val base = project.basePath ?: return DetectedTargets(false, false, TargetLanguage.JAVASCRIPT)
        val root = File(base)
        val hasJs = hasPlaywrightJs(root)
        val hasPy = hasPlaywrightPython(root)
        val settings = project.getService(PlaywrightSettingsService::class.java).get()
        val preferred = when (settings.preferredTarget) {
            PreferredTarget.JAVASCRIPT -> TargetLanguage.JAVASCRIPT
            PreferredTarget.PYTHON -> TargetLanguage.PYTHON
            PreferredTarget.AUTO -> when {
                hasJs && !hasPy -> TargetLanguage.JAVASCRIPT
                hasPy && !hasJs -> TargetLanguage.PYTHON
                else -> TargetLanguage.JAVASCRIPT
            }
        }
        return DetectedTargets(hasJs, hasPy, preferred)
    }

    fun isTypeScriptProject(project: Project): Boolean {
        val base = project.basePath ?: return false
        val root = File(base)
        return File(root, "tsconfig.json").exists() || File(root, "playwright.config.ts").exists()
    }

    private fun hasPlaywrightJs(root: File): Boolean {
        val pkg = File(root, "package.json")
        val nodeModules = File(root, "node_modules")
        val hasInstalled = File(nodeModules, "@playwright/test").exists() || File(nodeModules, "playwright").exists()
        if (hasInstalled) return true
        if (!pkg.exists()) return false
        val text = pkg.readText()
        return text.contains("\"@playwright/test\"") || text.contains("\"playwright\"")
    }

    private fun hasPlaywrightPython(root: File): Boolean {
        val req = File(root, "requirements.txt")
        val pyproject = File(root, "pyproject.toml")
        return (req.exists() && req.readText().contains("playwright")) ||
            (pyproject.exists() && pyproject.readText().contains("playwright"))
    }
}
