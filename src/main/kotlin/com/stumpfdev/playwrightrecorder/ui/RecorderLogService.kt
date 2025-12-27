package com.stumpfdev.playwrightrecorder.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class RecorderLogService(private val project: Project) {
    private val logs = ArrayDeque<String>()
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    var onLogUpdated: ((List<String>) -> Unit)? = null

    fun info(message: String) {
        add("[${LocalTime.now().format(formatter)}] $message")
    }

    fun error(message: String) {
        add("[${LocalTime.now().format(formatter)}] ERROR: $message")
    }

    private fun add(message: String) {
        if (logs.size >= 200) {
            logs.removeFirst()
        }
        logs.addLast(message)
        onLogUpdated?.invoke(logs.toList())
    }
}
