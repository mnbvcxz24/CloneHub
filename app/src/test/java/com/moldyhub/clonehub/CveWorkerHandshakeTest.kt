package com.moldyhub.clonehub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CveWorkerHandshakeTest {
    @Test
    fun acceptsCurrentProtocolAndDistinctUid() {
        val handshake = CveWorkerHandshake(
            protocolVersion = CveWorkerProtocol.VERSION,
            processId = 1234,
            workerUid = 99001
        )
        assertTrue(handshake.isIsolatedFrom(10123))
    }

    @Test
    fun rejectsHostUid() {
        val handshake = CveWorkerHandshake(
            protocolVersion = CveWorkerProtocol.VERSION,
            processId = 1234,
            workerUid = 10123
        )
        assertFalse(handshake.isIsolatedFrom(10123))
    }

    @Test
    fun rejectsUnsupportedProtocol() {
        val handshake = CveWorkerHandshake(
            protocolVersion = CveWorkerProtocol.VERSION + 1,
            processId = 1234,
            workerUid = 99001
        )
        assertFalse(handshake.isIsolatedFrom(10123))
    }

    @Test
    fun rejectsInvalidProcessId() {
        val handshake = CveWorkerHandshake(
            protocolVersion = CveWorkerProtocol.VERSION,
            processId = -1,
            workerUid = 99001
        )
        assertFalse(handshake.isIsolatedFrom(10123))
    }
}
