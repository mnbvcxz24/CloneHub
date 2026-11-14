package com.moldyhub.clonehub

import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

/**
 * Opt-in adapter for the pinned NewBlackbox engine.
 *
 * The engine is deliberately not loaded during CloneHub startup. It is initialized
 * only after the user taps Install/Launch, so a backend failure cannot prevent the
 * dashboard from opening.
 */
internal object NewBlackboxRuntime {
    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    private var initializationFailure: String? = null

    private var core: BlackBoxCore? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        try {
            val engine = BlackBoxCore.get()
            val configuration = object : ClientConfiguration() {
                override fun getHostPackageName(): String = appContext.packageName
                override fun isEnableDaemonService(): Boolean = false
                override fun isEnableLauncherActivity(): Boolean = true
            }
            engine.doAttachBaseContext(appContext, configuration)
            engine.doCreate()
            check(engine.areServicesAvailable()) {
                "NewBlackbox services did not become available."
            }
            core = engine
            initialized = true
            initializationFailure = null
            DiagnosticsLogger.record(appContext, "NewBlackbox initialized on demand.")
        } catch (error: Throwable) {
            initializationFailure = error.message ?: error.javaClass.simpleName
            DiagnosticsLogger.record(
                appContext,
                "NewBlackbox initialization failed: ${error.javaClass.name}: ${initializationFailure}"
            )
            throw IllegalStateException("NewBlackbox initialization failed: ${initializationFailure}", error)
        }
    }

    fun statusText(): String = initializationFailure?.let {
        "NewBlackbox initialization failed: $it"
    } ?: "NewBlackbox is not initialized. Tap Install on an instance to start it."

    fun allocateUserId(context: Context): Int {
        initialize(context)
        val engine = requireCore()
        val nextId = (engine.users.maxOfOrNull { it.id } ?: 0) + 1
        val user = engine.createUser(nextId)
            ?: throw IllegalStateException("NewBlackbox could not create virtual user $nextId.")
        check(user.id == nextId) { "NewBlackbox returned unexpected virtual user ID ${user.id}." }
        return user.id
    }

    fun installApp(packageName: String, userId: Int) {
        val result = requireCore().installPackageAsUser(packageName, userId)
        check(result.success) {
            result.msg?.takeIf { it.isNotBlank() } ?: "NewBlackbox failed to install $packageName."
        }
    }

    fun launchApp(packageName: String, userId: Int): Boolean =
        requireCore().launchApk(packageName, userId)

    fun deleteUser(userId: Int) {
        requireCore().deleteUser(userId)
    }

    private fun requireCore(): BlackBoxCore =
        core ?: throw IllegalStateException(statusText())
}
