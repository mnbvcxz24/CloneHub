package com.moldyhub.clonehub

import com.moldyhub.clonehub.cve.InstanceState
import com.moldyhub.clonehub.cve.VirtualAppInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualAppLifecycleControllerTest {
    @Test
    fun successfulBackendStartAndStopPersistTransitions() {
        val repository = InMemoryRepository(VirtualAppInstance.create("com.example.notes"))
        val runtime = FakeRuntime(
            startResult = RuntimeOperationResult.Success,
            stopResult = RuntimeOperationResult.Success
        )
        val controller = VirtualAppLifecycleController(repository, runtime)

        val started = controller.start(repository.instance.instanceId)
        assertTrue(started is InstanceLifecycleResult.Running)
        assertEquals(InstanceState.RUNNING, repository.instance.state)

        val stopped = controller.stop(repository.instance.instanceId)
        assertTrue(stopped is InstanceLifecycleResult.Stopped)
        assertEquals(InstanceState.STOPPED, repository.instance.state)
    }

    @Test
    fun failedBackendNeverMarksInstanceRunning() {
        val repository = InMemoryRepository(VirtualAppInstance.create("com.example.notes"))
        val controller = VirtualAppLifecycleController(
            repository,
            FakeRuntime(RuntimeOperationResult.Failure("backend unavailable"))
        )

        val result = controller.start(repository.instance.instanceId)

        assertTrue(result is InstanceLifecycleResult.Failed)
        assertEquals(InstanceState.FAILED, repository.instance.state)
    }

    @Test
    fun unavailableRuntimeFailsSafely() {
        val repository = InMemoryRepository(VirtualAppInstance.create("com.example.notes"))
        val controller = VirtualAppLifecycleController(repository, UnavailableVirtualAppRuntime())

        val result = controller.start(repository.instance.instanceId)

        assertTrue(result is InstanceLifecycleResult.Failed)
        assertEquals(InstanceState.FAILED, repository.instance.state)
    }

    @Test
    fun missingInstanceIsReportedWithoutCreatingFakeRecord() {
        val repository = InMemoryRepository(VirtualAppInstance.create("com.example.notes"))
        val controller = VirtualAppLifecycleController(repository, UnavailableVirtualAppRuntime())

        val result = controller.start("missing-id")

        assertTrue(result is InstanceLifecycleResult.Failed)
        assertEquals(null, (result as InstanceLifecycleResult.Failed).instance)
        assertEquals(InstanceState.STOPPED, repository.instance.state)
    }

    private class InMemoryRepository(initial: VirtualAppInstance) : VirtualAppInstanceRepository {
        var instance: VirtualAppInstance = initial
            private set

        override fun getById(instanceId: String): VirtualAppInstance? =
            instance.takeIf { it.instanceId == instanceId }

        override fun update(instance: VirtualAppInstance): VirtualAppInstance {
            require(instance.instanceId == this.instance.instanceId)
            this.instance = instance
            return instance
        }
    }

    private class FakeRuntime(
        private val startResult: RuntimeOperationResult,
        private val stopResult: RuntimeOperationResult = RuntimeOperationResult.Failure("not configured")
    ) : InstanceRuntimeBackend {
        override fun start(instance: VirtualAppInstance): RuntimeOperationResult = startResult
        override fun stop(instance: VirtualAppInstance): RuntimeOperationResult = stopResult
    }
}
