package com.moldyhub.clonehub

import android.app.Application
import android.content.Context

/**
 * Boots BlackBox early in the host process. If startup is interrupted before
 * Application.onCreate(), the next launch disables the backend automatically
 * so CloneHub can recover instead of entering a crash loop.
 */
class CloneHubApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        DiagnosticsLogger.installCrashHandler(base)

        val prefs = base.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            DiagnosticsLogger.record(base, "BlackBox runtime startup disabled")
            return
        }

        val isHostProcess = Application.getProcessName() == base.packageName
        if (isHostProcess && prefs.getBoolean(KEY_BOOT_IN_PROGRESS, false)) {
            prefs.edit()
                .putBoolean(KEY_ENABLED, false)
                .putBoolean(KEY_BOOT_IN_PROGRESS, false)
                .commit()
            DiagnosticsLogger.record(
                base,
                "BlackBox startup was interrupted on the previous launch; runtime disabled for recovery"
            )
            return
        }

        if (isHostProcess) {
            prefs.edit().putBoolean(KEY_BOOT_IN_PROGRESS, true).commit()
        }
        BlackBoxRuntime.attach(this, base)
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, true)) return

        BlackBoxRuntime.create(this)
        if (Application.getProcessName() == packageName) {
            prefs.edit().putBoolean(KEY_BOOT_IN_PROGRESS, false).commit()
        }
    }

    companion object {
        private const val RUNTIME_PREFS = "clonehub_runtime_boot"
        private const val KEY_ENABLED = "blackbox_enabled"
        private const val KEY_BOOT_IN_PROGRESS = "blackbox_boot_in_progress"
    }
}
