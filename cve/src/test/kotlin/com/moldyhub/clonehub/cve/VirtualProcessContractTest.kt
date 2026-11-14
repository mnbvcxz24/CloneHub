package com.moldyhub.clonehub.cve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VirtualProcessContractTest {
    @Test
    fun acceptsValidInstanceLaunchRequest() {
        val request = VirtualProcessRequest(
            instanceId = "instance_123",
            packageName = "com.roblox.client",
            apkSetVersionCode = 20836,
            workspacePath = "/data/user/0/com.moldyhub.clonehub/files/runtime_sessions/instance_123"
        )

        assertEquals("instance_123", request.instanceId)
        assertEquals("com.roblox.client", request.packageName)
        assertEquals(20836L, request.apkSetVersionCode)
    }

    @Test
    fun rejectsUnsafeInstanceIdentifier() {
        assertThrows(IllegalArgumentException::class.java) {
            VirtualProcessRequest("../outside", "com.roblox.client", 1, "/tmp/session")
        }
    }

    @Test
    fun rejectsInvalidPackageName() {
        assertThrows(IllegalArgumentException::class.java) {
            VirtualProcessRequest("instance_1", "roblox", 1, "/tmp/session")
        }
    }

    @Test
    fun rejectsMissingApkVersionOrWorkspace() {
        assertThrows(IllegalArgumentException::class.java) {
            VirtualProcessRequest("instance_1", "com.example.app", 0, "/tmp/session")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualProcessRequest("instance_1", "com.example.app", 1, " ")
        }
    }

    @Test
    fun processIdentityRequiresRealProcessMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            VirtualProcessIdentity("instance_1", "com.example.app", 0, 10001, "com.example.app")
        }
    }

    @Test
    fun acceptsStartedIdentityOnlyWithPositivePid() {
        val identity = VirtualProcessIdentity(
            instanceId = "instance_1",
            packageName = "com.example.app",
            processId = 1234,
            uid = 10001,
            processName = "com.example.app"
        )

        assertEquals(1234, identity.processId)
    }
}
