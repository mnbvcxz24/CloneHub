package com.moldyhub.clonehub.cve

/**
 * Validated request crossing the future CVE process-backend boundary.
 *
 * This is a contract only: it does not create Android processes, install APKs,
 * or provide per-instance app-data isolation.
 */
data class VirtualProcessRequest(
    val instanceId: String,
    val packageName: String,
    val apkSetVersionCode: Long,
    val workspacePath: String
) {
    init {
        require(INSTANCE_ID.matches(instanceId)) {
            "instanceId must be a safe, non-blank identifier"
        }
        require(PACKAGE_NAME.matches(packageName)) {
            "packageName must be a valid Android-style package name"
        }
        require(apkSetVersionCode > 0) {
            "apkSetVersionCode must be positive"
        }
        require(workspacePath.isNotBlank()) {
            "workspacePath must not be blank"
        }
    }

    companion object {
        private val INSTANCE_ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

/**
 * Evidence returned by a future backend after it has actually started a process.
 * A worker handshake alone must never be converted into this value.
 */
data class VirtualProcessIdentity(
    val instanceId: String,
    val packageName: String,
    val processId: Int,
    val uid: Int,
    val processName: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(processId > 0) { "processId must be positive" }
        require(uid >= 0) { "uid must not be negative" }
        require(processName.isNotBlank()) { "processName must not be blank" }
    }
}

/**
 * Implementations must only return Started after the target process is genuinely
 * running and bound to the requested instance. The current CloneHub worker is not
 * an implementation of this interface.
 */
interface VirtualProcessBackend {
    fun start(request: VirtualProcessRequest): VirtualProcessStartResult
    fun stop(process: VirtualProcessIdentity): VirtualProcessStopResult
}

sealed class VirtualProcessStartResult {
    data class Started(val process: VirtualProcessIdentity) : VirtualProcessStartResult()
    data class Rejected(val reason: String) : VirtualProcessStartResult()
}

sealed class VirtualProcessStopResult {
    object Stopped : VirtualProcessStopResult()
    data class Rejected(val reason: String) : VirtualProcessStopResult()
}
