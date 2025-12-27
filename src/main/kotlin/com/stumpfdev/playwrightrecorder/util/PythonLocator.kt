package com.stumpfdev.playwrightrecorder.util

import com.intellij.openapi.project.Project
import java.io.File

object PythonLocator {
    fun resolvePythonExecutable(project: Project): String {
        val base = project.basePath ?: return defaultPython()
        val root = File(base)
        val venvCandidates = listOf(".venv", "venv", "env")
        for (name in venvCandidates) {
            val dir = File(root, name)
            val bin = if (isWindows()) File(dir, "Scripts\\python.exe") else File(dir, "bin/python")
            if (bin.exists()) return bin.absolutePath
        }
        return defaultPython()
    }

    private fun defaultPython(): String = if (isWindows()) "python" else "python3"

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
