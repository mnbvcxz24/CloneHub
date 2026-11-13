package com.moldyhub.clonehub.cve

import java.util.UUID

/**
 * Stable identity for one virtual instance of an installed package.
 * The package name identifies the app; the instance ID distinguishes its clones.
 */
data class VirtualAppIdentity(
    val instanceId: String,
    val packageName: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(PACKAGE_NAME_PATTERN.matches(packageName)) {
            "Invalid Android package name: $packageName"
        }
    }

    companion object {
        private val PACKAGE_NAME_PATTERN =
            Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

        /** Creates a new identity without changing the installed package. */
        fun create(packageName: String): VirtualAppIdentity =
            VirtualAppIdentity(
                instanceId = UUID.randomUUID().toString(),
                packageName = packageName
            )
    }
}
