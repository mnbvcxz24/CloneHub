package com.moldyhub.clonehub.cve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
