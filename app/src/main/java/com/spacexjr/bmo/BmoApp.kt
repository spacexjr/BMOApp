package com.spacexjr.bmo

import android.app.Application
import com.spacexjr.bmo.data.DiagnosticsManager
import com.spacexjr.bmo.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BmoApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var diagnostics: DiagnosticsManager
        private set
    lateinit var assistant: BmoAssistant
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        diagnostics = DiagnosticsManager()
        assistant = BmoAssistant(this, appScope, settingsRepository, diagnostics)
        diagnostics.info("App", "BMO App iniciado")
    }
}
