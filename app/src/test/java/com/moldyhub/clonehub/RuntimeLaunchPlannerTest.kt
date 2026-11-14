package com.moldyhub.clonehub

import com.moldyhub.clonehub.cve.VirtualAppConfiguration
import com.moldyhub.clonehub.cve.VirtualAppInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeLaunchPlannerTest {
    @Test
    fun createsPlanAndDedicatedWorkspaceForVerifiedApkSet() {
        val root = Files.createTempDirectory("clonehub-runtime-plan").toFile()
        try {
            val apkRoot = File(root, "prepared_apks")
            val setDirectory = File(apkRoot, "com.example.game/42").apply { mkdirs() }
            File(setDirectory, "apk-set.json").writeText("{}")
            File(setDirectory, "base.apk").writeBytes(byteArrayOf(1, 2, 3))
            File(setDirectory, "split_1.apk").writeBytes(byteArrayOf(4, 5))

            val instance = VirtualAppInstance.create(
                "com.example.game",
                VirtualAppConfiguration(displayName = "Instance 1", apkSetVersionCode = 42L)
            )
            val planner = RuntimeLaunchPlanner(
                ApkSetResolver(apkRoot),
                File(root, "runtime_sessions")
            )

            val result = planner.createPlan(instance, 35, listOf("arm64-v8a", "armeabi-v7a"))

            assertTrue(result is RuntimeLaunchPlanningResult.Ready)
            val plan = (result as RuntimeLaunchPlanningResult.Ready).plan
            assertEquals(42L, plan.apkSetVersionCode)
            assertEquals(listOf("base.apk", "split_1.apk"), plan.apkFiles.map { it.name })
            assertEquals(5L, plan.totalApkBytes)
            assertEquals(listOf("arm64-v8a", "armeabi-v7a"), plan.supportedAbis)
            assertTrue(plan.workspaceDirectory.isDirectory)
            assertEquals(instance.instanceId, plan.workspaceDirectory.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafeInstanceIdBeforeCreatingWorkspace() {
        val root = Files.createTempDirectory("clonehub-runtime-plan").toFile()
        try {
            val instance = VirtualAppInstance.create("com.example.game").copy(instanceId = "../outside")
            val planner = RuntimeLaunchPlanner(
                ApkSetResolver(File(root, "prepared_apks")),
                File(root, "runtime_sessions")
            )

            val result = planner.createPlan(instance, 35, listOf("arm64-v8a"))

            assertTrue(result is RuntimeLaunchPlanningResult.Failure)
            assertTrue(!File(root, "outside").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsupportedAndroidVersion() {
        val root = Files.createTempDirectory("clonehub-runtime-plan").toFile()
        try {
            val instance = VirtualAppInstance.create("com.example.game")
            val planner = RuntimeLaunchPlanner(
                ApkSetResolver(File(root, "prepared_apks")),
                File(root, "runtime_sessions")
            )

            val result = planner.createPlan(instance, 28, listOf("arm64-v8a"))

            assertTrue(result is RuntimeLaunchPlanningResult.Failure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingDeviceAbiInformation() {
        val root = Files.createTempDirectory("clonehub-runtime-plan").toFile()
        try {
            val instance = VirtualAppInstance.create("com.example.game")
            val planner = RuntimeLaunchPlanner(
                ApkSetResolver(File(root, "prepared_apks")),
                File(root, "runtime_sessions")
            )

            val result = planner.createPlan(instance, 35, emptyList())

            assertTrue(result is RuntimeLaunchPlanningResult.Failure)
        } finally {
            root.deleteRecursively()
        }
    }
}
