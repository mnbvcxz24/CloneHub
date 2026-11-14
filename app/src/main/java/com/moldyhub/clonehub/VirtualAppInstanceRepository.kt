package com.moldyhub.clonehub

import com.moldyhub.clonehub.cve.VirtualAppInstance

/** Persistence boundary used by the lifecycle coordinator. */
interface VirtualAppInstanceRepository {
    fun getById(instanceId: String): VirtualAppInstance?
    fun update(instance: VirtualAppInstance): VirtualAppInstance
}
