package com.stumpfdev.playwrightrecorder.util

import com.intellij.openapi.project.Project
import java.io.File

object NodeLocator {
    fun resolveNode(): String {
        return if (isWindows()) "node.exe" else "node"
    }

    fun resolveNpm(project: Project): String {
        val base = project.basePath ?: return defaultNpm()
        val root = File(base)
        val local = File(root, "node_modules/.bin/${if (isWindows()) "npm.cmd" else "npm"}")
        return if (local.exists()) local.absolutePath else defaultNpm()
    }

    fun resolveNpx(project: Project): String {
        val base = project.basePath ?: return defaultNpx()
        val root = File(base)
        val local = File(root, "node_modules/.bin/${if (isWindows()) "npx.cmd" else "npx"}")
        return if (local.exists()) local.absolutePath else defaultNpx()
    }

    fun resolvePlaywrightBin(project: Project): String? {
        val base = project.basePath ?: return null
        val root = File(base)
        val local = File(root, "node_modules/.bin/${if (isWindows()) "playwright.cmd" else "playwright"}")
        return if (local.exists()) local.absolutePath else null
    }

    private fun defaultNpx(): String = if (isWindows()) "npx.cmd" else "npx"
    private fun defaultNpm(): String = if (isWindows()) "npm.cmd" else "npm"

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
