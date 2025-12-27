package com.stumpfdev.playwrightrecorder.settings

enum class PreferredTarget {
    AUTO,
    JAVASCRIPT,
    PYTHON
}

data class PlaywrightSettingsState(
    var defaultUrl: String = "",
    var testsDir: String = "tests",
    var preferredTarget: PreferredTarget = PreferredTarget.AUTO
)
