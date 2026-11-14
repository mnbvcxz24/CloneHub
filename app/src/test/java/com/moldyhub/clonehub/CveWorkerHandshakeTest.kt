package com.moldyhub.clonehub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CveWorkerHandshakeTest {
    @Test
    fun acceptsVerifiedIsolatedWorkerWithSupportedDeviceCapabilities() {
        val handshake = CveWorkerHandshake(
            protocolVersion = CveWorkerProtocol.VERSION,
            processId = 1234,
            workerUid = 99001,
            deviceSdkInt = 35,
            supportedAbis = listOf("arm64-v8a")
        )
        assertTrue(handshake.isIsolatedFrom(10123))
    }

    @Test
    fun rejectsWorkerThatSharesHostUid() {
        val handshake = CveWorkerHandshake(
            protocolVersion = CveWorkerProtocol.VERSION,
            processId = 1234,
            workerUid = 10123,
            deviceSdkInt = 35,
            supportedAbis = listOf("arm64-v8a")
        )
        assertFalse(handshake.isIsolatedFrom(10123))
    }

    @Test
    fun rejectsMissingOrUnsupportedCapabilities() {
        val noAbi = CveWorkerHandshake(
            protocolVersion = CveWorkerProtocol.VERSION,
            processId = 1234,
            workerUid = 99001,
            deviceSdkInt = 35,
            supportedAbis = emptyList()
        )
        val oldAndroid = noAbi.copy(deviceSdkInt = 28, supportedAbis = listOf("arm64-v8a"))
        assertFalse(noAbi.isIsolatedFrom(10123))
        assertFalse(oldAndroid.isIsolatedFrom(10123))
    }

    @Test
    fun rejectsUnknownProtocolVersion() {
        val handshake = CveWorkerHandshake(
            protocolVersion = 1,
            processId = 1234,
            workerUid = 99001,
            deviceSdkInt = 35,
            supportedAbis = listOf("arm64-v8a")
        )
        assertFalse(handshake.isIsolatedFrom(10123))
    }
}
