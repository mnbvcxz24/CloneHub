package com.moldyhub.clonehub.cve

/**
 * User-facing configuration stored with a virtual instance.
 *
 * These values describe intended configuration only. They do not yet enforce
 * Android storage, permission, process, or notification isolation.
 */
data class VirtualAppConfiguration(
    val displayName: String? = null,
    val dataDirectoryName: String? = null
) {
    init {
        require(displayName == null || displayName.isNotBlank()) {
            "displayName must be null or non-blank"
        }
        require(dataDirectoryName == null || isSafeDirectoryName(dataDirectoryName)) {
            "dataDirectoryName must be a single safe path segment"
        }
    }

    private fun isSafeDirectoryName(value: String): Boolean =
        value.isNotBlank() &&
            value != "." &&
            value != ".." &&
            '/' !in value &&
            '\\' !in value
}
