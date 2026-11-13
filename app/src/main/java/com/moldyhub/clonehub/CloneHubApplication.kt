package com.moldyhub.clonehub

import android.app.Application
import android.content.Context

class CloneHubApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        DiagnosticsLogger.installCrashHandler(base)
        BlackBoxRuntime.attach(this, base)
    }

    override fun onCreate() {
        super.onCreate()
        BlackBoxRuntime.create(this)
    }
}
