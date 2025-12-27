package com.stumpfdev.playwrightrecorder.util

import java.io.File

object FileUtil {
    fun ensureDir(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }
}
