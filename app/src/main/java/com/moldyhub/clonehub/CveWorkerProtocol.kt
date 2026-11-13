package com.moldyhub.clonehub

/** Stable IPC contract between CloneHub and its isolated CVE worker process. */
object CveWorkerProtocol {
    const val VERSION = 1
    const val MSG_PING = 1
    const val MSG_PONG = 2
    const val KEY_PROCESS_ID = "cve_worker_pid"
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
