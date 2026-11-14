package com.moldyhub.clonehub

import com.moldyhub.clonehub.cve.VirtualAppInstance

/**
 * Prepares and validates backend inputs, but intentionally does not execute target APK code.
 * A real backend must replace this adapter before an instance can become RUNNING.
 */
class PreparedApkSetRuntime(
    private val planner: RuntimeLaunchPlanner,
    private val deviceSdkInt: Int,
    private val supportedAbis: List<String>
) : VirtualAppRuntime {
    override fun start(instance: VirtualAppInstance): RuntimeOperationResult =
        when (val result = planner.createPlan(instance, deviceSdkInt, supportedAbis)) {
            is RuntimeLaunchPlanningResult.Ready -> {
                val plan = result.plan
                RuntimeOperationResult.Failure(
                    "Launch plan prepared for ${plan.packageName} " +
                        "(Android API ${plan.deviceSdkInt}, ${plan.supportedAbis.joinToString()}), " +
                        "but the virtual execution backend is not implemented yet."
                )
            }
            is RuntimeLaunchPlanningResult.Failure -> RuntimeOperationResult.Failure(result.reason)
        }

    override fun stop(instance: VirtualAppInstance): RuntimeOperationResult =
        RuntimeOperationResult.Failure(
            "No virtual app process is running because the execution backend is not connected."
        )
}
