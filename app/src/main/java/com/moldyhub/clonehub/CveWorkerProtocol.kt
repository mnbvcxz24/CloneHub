package com.moldyhub.clonehub

/** Versioned IPC contract for the isolated CVE worker capability handshake. */
object CveWorkerProtocol {
    const val VERSION = 2
    const val MSG_PING = 1
    const val MSG_PONG = 2
    const val KEY_PROCESS_ID = "cve_worker_pid"
    const val KEY_DEVICE_SDK = "cve_worker_device_sdk"
    const val KEY_SUPPORTED_ABIS = "cve_worker_supported_abis"
}

/**
 * Reports worker process facts only. This does not mean a target APK can run
 * inside the worker or that per-app Android sandboxing has been established.
 */
data class CveWorkerHandshake(
    val protocolVersion: Int,
    val processId: Int,
    val workerUid: Int,
    val deviceSdkInt: Int,
    val supportedAbis: List<String>
) {
    fun isIsolatedFrom(hostUid: Int): Boolean =
        protocolVersion == CveWorkerProtocol.VERSION &&
            processId > 0 &&
            workerUid >= 0 &&
            workerUid != hostUid &&
            deviceSdkInt >= 29 &&
            supportedAbis.any { it.isNotBlank() }
}
