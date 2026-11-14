package com.moldyhub.clonehub

/**
 * Alpha23 isolation stub.
 *
 * BlackBox sources remain in third_party, but no BlackBox module is included
 * in the app build and no BlackBox classes are loaded or initialized.
 * Runtime actions remain unavailable until the engine is reintroduced safely.
 */
internal object BlackBoxRuntime {
    @Volatile
    var initialized: Boolean = false
        private set

    fun statusText(): String = "BlackBox excluded from Alpha23 isolation build"

    fun allocateUserId(context: android.content.Context): Int =
        throw IllegalStateException(statusText())

    fun installApp(packageName: String, userId: Int) {
        throw IllegalStateException(statusText())
    }

    fun launchApp(packageName: String, userId: Int): Boolean =
        throw IllegalStateException(statusText())

    fun deleteUser(userId: Int) {
        throw IllegalStateException(statusText())
    }
}
