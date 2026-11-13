package com.moldyhub.clonehub

import android.app.Application
import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import top.niunaijun.blackbox.fake.frameworks.BUserManager

/**
 * Owns the small integration surface between CloneHub and the pinned BlackBox core.
 * Runtime calls are kept here so UI code never has to manage BlackBox internals.
 */
internal object BlackBoxRuntime {
    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    var initializationError: String? = null
        private set

    fun attach(application: Application, base: Context) {
        try {
            val core = BlackBoxCore.get()
            core.closeCodeInit()
            core.onBeforeMainApplicationAttach(application, base)
            core.doAttachBaseContext(
                base,
                object : ClientConfiguration() {
                    override fun getHostPackageName(): String = base.packageName
                }
            )
            core.onAfterMainApplicationAttach(application, base)
            DiagnosticsLogger.record(base, "BlackBox core attached")
        } catch (error: Throwable) {
            initializationError = error.message ?: error.javaClass.simpleName
            DiagnosticsLogger.record(
                base,
                "BlackBox attach failed: ${error.javaClass.name}: ${error.message}"
            )
        }
    }

    fun create(context: Context) {
        try {
            BlackBoxCore.get().doCreate()
            initialized = true
            initializationError = null
            DiagnosticsLogger.record(context, "BlackBox core initialization completed")
        } catch (error: Throwable) {
            initializationError = error.message ?: error.javaClass.simpleName
            DiagnosticsLogger.record(
                context,
                "BlackBox initialization failed: ${error.javaClass.name}: ${error.message}"
            )
        }
    }

    fun statusText(): String = when {
        initialized -> "BlackBox engine initialized"
        initializationError != null -> "BlackBox unavailable: $initializationError"
        else -> "BlackBox engine starting"
    }

    fun allocateUserId(context: Context): Int {
        check(initialized) { initializationError ?: "BlackBox runtime is not initialized" }
        val preferences = context.getSharedPreferences("clonehub_library", Context.MODE_PRIVATE)
        val existingIds = runCatching {
            BUserManager.get().getUsers().map { it.id }.toSet()
        }.getOrDefault(emptySet())

        var candidate = preferences.getInt(KEY_NEXT_VIRTUAL_USER_ID, 1).coerceAtLeast(1)
        while (candidate == 0 || candidate in existingIds) candidate++
        preferences.edit().putInt(KEY_NEXT_VIRTUAL_USER_ID, candidate + 1).apply()
        return candidate
    }

    fun installApp(packageName: String, userId: Int) {
        check(initialized) { initializationError ?: "BlackBox runtime is not initialized" }
        val core = BlackBoxCore.get()
        check(core.areServicesAvailable()) { "BlackBox services are not available yet" }

        val user = core.createUser(userId)
            ?: throw IllegalStateException("BlackBox could not create virtual user $userId")

        if (!core.isInstalled(packageName, user.id)) {
            val result = core.installPackageAsUser(packageName, user.id)
            if (!result.success) {
                core.deleteUser(user.id)
                throw IllegalStateException(result.msg ?: "Virtual app installation failed")
            }
        }
    }

    fun launchApp(packageName: String, userId: Int): Boolean {
        check(initialized) { initializationError ?: "BlackBox runtime is not initialized" }
        val core = BlackBoxCore.get()
        if (!core.isInstalled(packageName, userId)) return false
        return core.launchApk(packageName, userId)
    }

    fun deleteUser(userId: Int) {
        check(initialized) { initializationError ?: "BlackBox runtime is not initialized" }
        require(userId > 0) { "Refusing to delete the default virtual user" }
        BlackBoxCore.get().deleteUser(userId)
    }

    private const val KEY_NEXT_VIRTUAL_USER_ID = "next_virtual_user_id"
}
