package com.moldyhub.clonehub

import android.app.Application
import android.content.Context

/**
 * Keeps the CloneHub dashboard process independent from VirtualApp until the
 * user starts an instance. VirtualApp's server and guest processes attach early,
 * as required by the engine's process model.
 */
class CloneHubApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        DiagnosticsLogger.installCrashHandler(base)
        DiagnosticsLogger.record(base, "CloneHub host startup: VirtualApp initialization deferred until user action")
        if (isVirtualRuntimeProcess(base)) {
            VirtualAppRuntime.attach(base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (isVirtualRuntimeProcess(this)) {
            VirtualAppRuntime.initialize(this)
        }
    }

    private fun isVirtualRuntimeProcess(context: Context): Boolean {
        val processName = Application.getProcessName()
        return processName != context.packageName && !processName.endsWith(":cve_worker")
    }
}
