package com.moldyhub.clonehub.cve

/**
 * Platform-independent model for one requested virtual app instance.
 *
 * This is a domain model only; it does not launch or isolate Android apps.
 */
data class VirtualAppInstance(
    val instanceId: String,
    val packageName: String,
    val state: InstanceState = InstanceState.STOPPED,
    val configuration: VirtualAppConfiguration = VirtualAppConfiguration()
) {
    val identity: VirtualAppIdentity
        get() = VirtualAppIdentity(instanceId, packageName)

    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }

    fun transitionTo(next: InstanceState): VirtualAppInstance {
        require(next in state.allowedNextStates) {
            "Invalid instance transition: $state -> $next"
        }
        return copy(state = next)
    }

    companion object {
        fun create(
            packageName: String,
            configuration: VirtualAppConfiguration = VirtualAppConfiguration()
        ): VirtualAppInstance {
            val identity = VirtualAppIdentity.create(packageName)
            return VirtualAppInstance(
                instanceId = identity.instanceId,
                packageName = identity.packageName,
                configuration = configuration
            )
        }
    }
}

enum class InstanceState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED;

    internal val allowedNextStates: Set<InstanceState>
        get() = when (this) {
            STOPPED -> setOf(STARTING)
            STARTING -> setOf(RUNNING, STOPPED, FAILED)
            RUNNING -> setOf(STOPPING, FAILED)
            STOPPING -> setOf(STOPPED, FAILED)
            FAILED -> setOf(STOPPED)
        }
}
