package com.stumpfdev.playwrightrecorder.util

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

object ClipboardUtil {
    fun readText(): String? {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        return try {
            clipboard.getData(DataFlavor.stringFlavor) as? String
        } catch (_: Exception) {
            null
        }
    }
}
