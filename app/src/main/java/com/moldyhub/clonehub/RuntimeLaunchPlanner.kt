package com.moldyhub.clonehub

import com.moldyhub.clonehub.cve.VirtualAppInstance
import java.io.File

/** Immutable, validated inputs for a future virtual-app execution backend. */
data class RuntimeLaunchPlan(
    val instanceId: String,
    val packageName: String,
    val apkSetVersionCode: Long,
    val apkFiles: List<File>,
    val totalApkBytes: Long,
    val workspaceDirectory: File,
    val deviceSdkInt: Int,
    val supportedAbis: List<String>
)

sealed class RuntimeLaunchPlanningResult {
    data class Ready(val plan: RuntimeLaunchPlan) : RuntimeLaunchPlanningResult()
    data class Failure(val reason: String) : RuntimeLaunchPlanningResult()
}

/**
 * Builds a launch plan without loading APK code, installing packages, or starting processes.
 * The workspace is CloneHub-owned scratch space, not target-app private storage or a sandbox.
 */
class RuntimeLaunchPlanner(
    private val apkSetResolver: ApkSetResolver,
    private val workspaceRoot: File
) {
    fun createPlan(
        instance: VirtualAppInstance,
        deviceSdkInt: Int,
        supportedAbis: List<String>
    ): RuntimeLaunchPlanningResult {
        if (!INSTANCE_ID.matches(instance.instanceId)) {
            return RuntimeLaunchPlanningResult.Failure("The instance ID cannot be used as a runtime workspace name.")
        }
        if (deviceSdkInt < 29) {
            return RuntimeLaunchPlanningResult.Failure("This CloneHub build requires Android API 29 or newer.")
        }
        val abis = supportedAbis.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (abis.isEmpty()) {
            return RuntimeLaunchPlanningResult.Failure("Android did not report a supported CPU architecture.")
        }

        val apkSet = when (val resolution = apkSetResolver.resolve(instance)) {
            is ApkSetResolution.Success -> resolution.apkSet
            is ApkSetResolution.Failure -> return RuntimeLaunchPlanningResult.Failure(resolution.reason)
        }

        return try {
            val root = workspaceRoot.canonicalFile
            if (!root.exists() && !root.mkdirs()) {
                return RuntimeLaunchPlanningResult.Failure("Could not create the CloneHub runtime workspace root.")
            }
            if (!root.isDirectory) {
                return RuntimeLaunchPlanningResult.Failure("The CloneHub runtime workspace root is not a directory.")
            }
            val workspace = File(root, instance.instanceId).canonicalFile
            if (workspace.parentFile != root) {
                return RuntimeLaunchPlanningResult.Failure("The runtime workspace path is outside CloneHub storage.")
            }
            if (!workspace.exists() && !workspace.mkdirs()) {
                return RuntimeLaunchPlanningResult.Failure("Could not create the instance runtime workspace.")
            }
            if (!workspace.isDirectory) {
                return RuntimeLaunchPlanningResult.Failure("The instance runtime workspace is not a directory.")
            }

            RuntimeLaunchPlanningResult.Ready(
                RuntimeLaunchPlan(
                    instanceId = instance.instanceId,
                    packageName = apkSet.packageName,
                    apkSetVersionCode = apkSet.versionCode,
                    apkFiles = apkSet.apkFiles,
                    totalApkBytes = apkSet.totalBytes,
                    workspaceDirectory = workspace,
                    deviceSdkInt = deviceSdkInt,
                    supportedAbis = abis
                )
            )
        } catch (error: Exception) {
            RuntimeLaunchPlanningResult.Failure(
                error.message ?: "Could not prepare the instance runtime workspace."
            )
        }
    }

    private companion object {
        val INSTANCE_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }
}
