package com.moldyhub.clonehub

import android.content.Context
import com.lody.virtual.client.core.InstallStrategy
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.stub.VASettings
import com.lody.virtual.os.VUserInfo
import com.lody.virtual.os.VUserManager

/**
 * CloneHub-owned adapter for the Apache-2.0 VirtualApp engine.
 *
 * The main dashboard process initializes the engine only after an explicit
 * Install/Launch action. VirtualApp's own server and guest processes attach
 * during Application startup so their Binder and lifecycle hooks are ready.
 */
internal object VirtualAppRuntime {
    @Volatile
    private var attached: Boolean = false

    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    private var initializationFailure: String? = null

    @Synchronized
    fun attach(context: Context) {
        if (attached) return
        val appContext = context.applicationContext
        try {
            VASettings.ENABLE_IO_REDIRECT = true
            VASettings.ENABLE_INNER_SHORTCUT = false
            // Xposed is not required for CloneHub's initial guest-runtime test.
            appContext.getFileStreamPath(".disable_xposed").let { flag ->
                if (!flag.exists()) flag.createNewFile()
            }
            VirtualCore.get().startup(appContext)
            attached = true
            DiagnosticsLogger.record(appContext, "VirtualApp core attached in process " + android.app.Application.getProcessName())
        } catch (error: Throwable) {
            initializationFailure = error.message ?: error.javaClass.simpleName
            DiagnosticsLogger.record(
                appContext,
                "VirtualApp attach failed: " + error.javaClass.name + ": " + initializationFailure
            )
            throw IllegalStateException("VirtualApp attach failed: " + initializationFailure, error)
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        attach(context)
        if (initialized) return
        val appContext = context.applicationContext
        try {
            VirtualCore.get().initialize(object : VirtualCore.VirtualInitializer() {})
            initialized = true
            initializationFailure = null
            DiagnosticsLogger.record(appContext, "VirtualApp runtime initialized.")
        } catch (error: Throwable) {
            initializationFailure = error.message ?: error.javaClass.simpleName
            DiagnosticsLogger.record(
                appContext,
                "VirtualApp initialization failed: " + error.javaClass.name + ": " + initializationFailure
            )
            throw IllegalStateException("VirtualApp initialization failed: " + initializationFailure, error)
        }
    }

    fun statusText(): String = initializationFailure?.let {
        "VirtualApp initialization failed: $it"
    } ?: if (initialized) {
        "VirtualApp runtime initialized."
    } else {
        "VirtualApp is not initialized. Tap Install on an instance to start it."
    }

    fun allocateUserId(context: Context, displayName: String): Int {
        initialize(context)
        val user = VUserManager.get().createUser(displayName, VUserInfo.FLAG_ADMIN)
            ?: throw IllegalStateException("VirtualApp could not create a guest user.")
        check(user.id > 0) { "VirtualApp returned an invalid guest user ID: " + user.id }
        return user.id
    }

    fun installApp(packageName: String, apkPath: String, userId: Int) {
        val core = requireCore()
        if (!core.isAppInstalled(packageName)) {
            val result = core.installPackage(
                apkPath,
                InstallStrategy.COMPARE_VERSION or InstallStrategy.SKIP_DEX_OPT
            )
            check(result.isSuccess) {
                result.error?.takeIf { it.isNotBlank() }
                    ?: "VirtualApp failed to import $packageName from $apkPath."
            }
        }
        check(core.installPackageAsUser(userId, packageName)) {
            "VirtualApp could not install $packageName into guest user $userId."
        }
    }

    fun launchApp(packageName: String, userId: Int): Boolean {
        val core = requireCore()
        val intent = core.getLaunchIntent(packageName, userId) ?: return false
        val result = VActivityManager.get().startActivity(intent, userId)
        DiagnosticsLogger.record(
            core.context,
            "VirtualApp launch dispatch: package=$packageName userId=$userId result=$result"
        )
        return result >= 0
    }

    fun deleteUser(userId: Int) {
        requireCore()
        check(VUserManager.get().removeUser(userId)) {
            "VirtualApp could not remove guest user $userId."
        }
    }

    private fun requireCore(): VirtualCore {
        check(initialized) { statusText() }
        return VirtualCore.get()
    }
}
