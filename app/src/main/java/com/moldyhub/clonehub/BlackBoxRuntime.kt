package com.moldyhub.clonehub

import android.app.Application
import android.content.Context

/**
 * Temporary no-op runtime used only by the startup isolation diagnostic build.
 * The real BlackBox implementation is restored after the minimal APK test.
 */
internal object BlackBoxRuntime {
    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    var initializationError: String? = "BlackBox excluded from diagnostic build"
        private set

    @Volatile
    private var diagnosticStartupSkipped: Boolean = true

    fun skipStartupForDiagnostics(context: Context) {
        diagnosticStartupSkipped = true
        initialized = false
        initializationError = "BlackBox excluded from diagnostic build"
    }

    fun attach(application: Application, base: Context) = Unit

    fun create(context: Context) = Unit

    fun statusText(): String = "BlackBox excluded (diagnostic build)"

    fun allocateUserId(context: Context): Int =
        error("BlackBox is disabled in this diagnostic build")

    fun installApp(packageName: String, userId: Int) {
        error("BlackBox is disabled in this diagnostic build")
    }

    fun launchApp(packageName: String, userId: Int): Boolean = false

    fun deleteUser(userId: Int) {
        error("BlackBox is disabled in this diagnostic build")
    }
}
