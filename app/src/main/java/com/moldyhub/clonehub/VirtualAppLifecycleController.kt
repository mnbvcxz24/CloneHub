package com.moldyhub.clonehub

import com.moldyhub.clonehub.cve.InstanceState
import com.moldyhub.clonehub.cve.VirtualAppInstance

/**
 * Boundary between CloneHub's lifecycle model and an Android virtual-app runtime.
 * A backend must confirm successful startup before an instance can become RUNNING.
 */
interface VirtualAppRuntime {
    fun start(instance: VirtualAppInstance): RuntimeOperationResult
    fun stop(instance: VirtualAppInstance): RuntimeOperationResult
}

sealed class RuntimeOperationResult {
    object Success : RuntimeOperationResult()
    data class Failure(val reason: String) : RuntimeOperationResult()
}

/**
 * Explicit placeholder until a real app execution backend is integrated.
 * It deliberately refuses to claim that an app has started or stopped.
 */
class UnavailableVirtualAppRuntime : VirtualAppRuntime {
    override fun start(instance: VirtualAppInstance): RuntimeOperationResult =
        RuntimeOperationResult.Failure(
            "The CloneHub Virtualization Engine runtime is not connected yet."
        )

    override fun stop(instance: VirtualAppInstance): RuntimeOperationResult =
        RuntimeOperationResult.Failure(
            "The CloneHub Virtualization Engine runtime is not connected yet."
        )
}

sealed class InstanceLifecycleResult {
    data class Running(val instance: VirtualAppInstance) : InstanceLifecycleResult()
    data class Stopped(val instance: VirtualAppInstance) : InstanceLifecycleResult()
    data class Failed(val instance: VirtualAppInstance?, val reason: String) : InstanceLifecycleResult()
}

/**
 * Coordinates persisted state transitions and delegates execution to a runtime backend.
 * RUNNING is persisted only after the backend reports success.
 */
class VirtualAppLifecycleController(
    private val store: VirtualAppInstanceRepository,
    private val runtime: VirtualAppRuntime
) {
    @Synchronized
    fun start(instanceId: String): InstanceLifecycleResult {
        var instance = store.getById(instanceId)
            ?: return missingInstance(instanceId)

        if (instance.state == InstanceState.FAILED) {
            instance = store.update(instance.transitionTo(InstanceState.STOPPED))
        }
        if (instance.state != InstanceState.STOPPED) {
            return InstanceLifecycleResult.Failed(
                instance,
                "Cannot start an instance while it is ${instance.state}."
            )
        }

        instance = store.update(instance.transitionTo(InstanceState.STARTING))
        return when (val result = runtime.start(instance)) {
            RuntimeOperationResult.Success -> {
                val running = store.update(instance.transitionTo(InstanceState.RUNNING))
                InstanceLifecycleResult.Running(running)
            }
            is RuntimeOperationResult.Failure -> {
                val failed = store.update(instance.transitionTo(InstanceState.FAILED))
                InstanceLifecycleResult.Failed(failed, result.reason)
            }
        }
    }

    @Synchronized
    fun stop(instanceId: String): InstanceLifecycleResult {
        val instance = store.getById(instanceId)
            ?: return missingInstance(instanceId)

        if (instance.state != InstanceState.RUNNING) {
            return InstanceLifecycleResult.Failed(
                instance,
                "Cannot stop an instance while it is ${instance.state}."
            )
        }

        val stopping = store.update(instance.transitionTo(InstanceState.STOPPING))
        return when (val result = runtime.stop(stopping)) {
            RuntimeOperationResult.Success -> {
                val stopped = store.update(stopping.transitionTo(InstanceState.STOPPED))
                InstanceLifecycleResult.Stopped(stopped)
            }
            is RuntimeOperationResult.Failure -> {
                val failed = store.update(stopping.transitionTo(InstanceState.FAILED))
                InstanceLifecycleResult.Failed(failed, result.reason)
            }
        }
    }

    private fun missingInstance(instanceId: String) =
        InstanceLifecycleResult.Failed(null, "Instance $instanceId was not found.")
}
