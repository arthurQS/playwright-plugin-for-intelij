package com.stumpfdev.playwrightrecorder.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "PlaywrightRecorderSettings", storages = [Storage("playwright-recorder.xml")])
class PlaywrightSettingsService(private val project: Project) : PersistentStateComponent<PlaywrightSettingsState> {
    private var state = PlaywrightSettingsState()

    override fun getState(): PlaywrightSettingsState = state

    override fun loadState(state: PlaywrightSettingsState) {
        this.state = state
    }

    fun get(): PlaywrightSettingsState = state
}
