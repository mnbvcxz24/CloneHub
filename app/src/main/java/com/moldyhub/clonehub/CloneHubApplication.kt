package com.moldyhub.clonehub

import android.app.Application
import android.content.Context

/**
 * Keeps host startup independent from the experimental NewBlackbox backend.
 * BlackBox is deliberately not attached during Application startup because
 * failures in framework hooks can prevent the dashboard from opening.
 */
class CloneHubApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        DiagnosticsLogger.installCrashHandler(base)
        DiagnosticsLogger.record(base, "Host startup safe mode: NewBlackbox initialization deferred until user action")
    }
}
