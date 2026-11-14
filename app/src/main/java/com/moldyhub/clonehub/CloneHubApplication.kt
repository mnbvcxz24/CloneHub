package com.moldyhub.clonehub

import android.app.Application
import android.content.Context

class CloneHubApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        DiagnosticsLogger.installCrashHandler(base)
        // Diagnostic isolation test: BlackBoxRuntime.attach() is intentionally skipped.
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogger.record(this, "DIAGNOSTIC TEST: BlackBox attach/create startup skipped")
    }
}
