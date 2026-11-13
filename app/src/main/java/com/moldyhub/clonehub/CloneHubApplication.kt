package com.moldyhub.clonehub

import android.app.Application
import android.content.Context

class CloneHubApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        DiagnosticsLogger.installCrashHandler(base)
        BlackBoxRuntime.skipStartupForDiagnostics(base)
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogger.record(this, "DIAGNOSTIC TEST: BlackBox doCreate skipped")
    }
}
