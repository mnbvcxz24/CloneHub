package com.moldyhub.clonehub.cve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class VirtualAppInstanceTest {
    @Test
    fun instanceStartsStoppedAndCanReachRunning() {
        val instance = VirtualAppInstance(
            instanceId = "instance-1",
            packageName = "com.example.notes"
        )

        val running = instance
            .transitionTo(InstanceState.STARTING)
            .transitionTo(InstanceState.RUNNING)

        assertEquals(InstanceState.STOPPED, instance.state)
        assertEquals(InstanceState.RUNNING, running.state)
        assertEquals("com.example.notes", running.packageName)
        assertEquals("instance-1", running.identity.instanceId)
    }

    @Test
    fun invalidTransitionIsRejected() {
        val instance = VirtualAppInstance(
            instanceId = "instance-2",
            packageName = "com.example.notes"
        )

        assertFailsWith<IllegalArgumentException> {
            instance.transitionTo(InstanceState.RUNNING)
        }
    }

    @Test
    fun failedInstanceCanBeResetToStopped() {
        val stopped = VirtualAppInstance(
            instanceId = "instance-3",
            packageName = "com.example.notes"
        )
            .transitionTo(InstanceState.STARTING)
            .transitionTo(InstanceState.FAILED)
            .transitionTo(InstanceState.STOPPED)

        assertEquals(InstanceState.STOPPED, stopped.state)
    }

    @Test
    fun factoryCreatesDistinctInstanceIdsForSamePackage() {
        val first = VirtualAppInstance.create("com.example.notes")
        val second = VirtualAppInstance.create("com.example.notes")

        assertNotEquals(first.instanceId, second.instanceId)
        assertEquals(first.packageName, second.packageName)
        assertNotNull(first.instanceId)
    }

    @Test
    fun invalidPackageNameIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            VirtualAppIdentity.create("not a package")
        }
    }

    @Test
    fun configurationIsStoredOnInstance() {
        val configuration = VirtualAppConfiguration(
            displayName = "Notes - Work",
            dataDirectoryName = "notes_work"
        )
        val instance = VirtualAppInstance.create(
            packageName = "com.example.notes",
            configuration = configuration
        )

        assertEquals(configuration, instance.configuration)
    }

    @Test
    fun unsafeDataDirectoryNameIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            VirtualAppConfiguration(dataDirectoryName = "../shared")
        }
    }
}
