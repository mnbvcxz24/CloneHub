package com.moldyhub.clonehub

import com.moldyhub.clonehub.cve.InstanceState
import com.moldyhub.clonehub.cve.VirtualAppConfiguration
import com.moldyhub.clonehub.cve.VirtualAppInstance

/**
 * Android-side entry point for the platform-independent CVE instance model.
 *
 * This registry is intentionally in-memory. It does not launch apps or provide
 * process, package-manager, permission, or storage isolation yet.
 */
class VirtualAppRegistry {
    private val instances = LinkedHashMap<String, VirtualAppInstance>()

    @Synchronized
    fun createInstance(
        packageName: String,
        displayName: String? = null
    ): VirtualAppInstance {
        val instance = VirtualAppInstance.create(
            packageName = packageName,
            configuration = VirtualAppConfiguration(displayName = displayName)
        )
        instances[instance.instanceId] = instance
        return instance
    }

    @Synchronized
    fun beginStart(instanceId: String): VirtualAppInstance =
        update(instanceId) { it.transitionTo(InstanceState.STARTING) }

    @Synchronized
    fun markRunning(instanceId: String): VirtualAppInstance =
        update(instanceId) { it.transitionTo(InstanceState.RUNNING) }

    @Synchronized
    fun markFailed(instanceId: String): VirtualAppInstance =
        update(instanceId) { it.transitionTo(InstanceState.FAILED) }

    @Synchronized
    fun stop(instanceId: String): VirtualAppInstance {
        val current = requireInstance(instanceId)
        val stopped = when (current.state) {
            InstanceState.STOPPED -> current
            InstanceState.STARTING, InstanceState.FAILED ->
                current.transitionTo(InstanceState.STOPPED)
            InstanceState.RUNNING ->
                current.transitionTo(InstanceState.STOPPING)
                    .transitionTo(InstanceState.STOPPED)
            InstanceState.STOPPING ->
                current.transitionTo(InstanceState.STOPPED)
        }
        instances[instanceId] = stopped
        return stopped
    }

    @Synchronized
    fun getInstance(instanceId: String): VirtualAppInstance? = instances[instanceId]

    @Synchronized
    fun getInstances(): List<VirtualAppInstance> = instances.values.toList()

    private fun update(
        instanceId: String,
        transform: (VirtualAppInstance) -> VirtualAppInstance
    ): VirtualAppInstance {
        val updated = transform(requireInstance(instanceId))
        instances[instanceId] = updated
        return updated
    }

    private fun requireInstance(instanceId: String): VirtualAppInstance =
        checkNotNull(instances[instanceId]) {
            "No virtual app instance exists with ID: $instanceId"
        }
}
