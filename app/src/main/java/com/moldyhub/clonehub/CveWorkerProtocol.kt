package com.moldyhub.clonehub

/** Stable IPC contract between CloneHub and its isolated CVE worker process. */
object CveWorkerProtocol {
    const val VERSION = 1
    const val MSG_PING = 1
    const val MSG_PONG = 2
    const val MSG_INSPECT_APK = 3
    const val MSG_APK_INSPECTED = 4

    const val KEY_PROCESS_ID = "cve_worker_pid"
    const val KEY_WORKER_UID = "cve_worker_uid"
    const val KEY_PACKAGE_NAME = "cve_package_name"
    const val KEY_APK_FD = "cve_apk_fd"
    const val KEY_SCAN_OK = "cve_scan_ok"
    const val KEY_ERROR = "cve_error"
    const val KEY_SHA256 = "cve_sha256"
    const val KEY_BYTES_READ = "cve_bytes_read"
    const val KEY_ENTRY_COUNT = "cve_entry_count"
    const val KEY_HAS_MANIFEST = "cve_has_manifest"
    const val KEY_DEX_COUNT = "cve_dex_count"
    const val KEY_NATIVE_ABIS = "cve_native_abis"
}

/** Proof that the worker answered from a different Android UID than the host app. */
data class CveWorkerHandshake(
    val protocolVersion: Int,
    val processId: Int,
    val workerUid: Int
) {
    fun isIsolatedFrom(hostUid: Int): Boolean =
        protocolVersion == CveWorkerProtocol.VERSION &&
            processId > 0 &&
            workerUid >= 0 &&
            workerUid != hostUid
}
