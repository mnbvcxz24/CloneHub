package com.moldyhub.clonehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCompatibilityEvaluatorTest {
    @Test
    fun compatibleAppPassesSdkLaunchAndAbiChecks() {
        val report = AppCompatibilityEvaluator.evaluate(
            descriptor(minSdk = 23, nativeAbis = listOf("arm64-v8a", "armeabi-v7a")),
            deviceApiLevel = 35,
            deviceSupportedAbis = listOf("arm64-v8a")
        )
        assertTrue(report.compatible)
        assertTrue(report.blockers.isEmpty())
    }

    @Test
    fun appRequiringNewerAndroidIsBlocked() {
        val report = AppCompatibilityEvaluator.evaluate(
            descriptor(minSdk = 36),
            deviceApiLevel = 35,
            deviceSupportedAbis = listOf("arm64-v8a")
        )
        assertFalse(report.compatible)
        assertTrue(report.blockers.any { it.contains("Requires Android API 36") })
    }

    @Test
    fun missingLaunchActivityIsBlocked() {
        val report = AppCompatibilityEvaluator.evaluate(
            descriptor(launchActivity = null),
            deviceApiLevel = 35,
            deviceSupportedAbis = emptyList()
        )
        assertFalse(report.compatible)
        assertTrue(report.blockers.any { it.contains("No launch activity") })
    }

    @Test
    fun incompatibleNativeAbiIsBlocked() {
        val report = AppCompatibilityEvaluator.evaluate(
            descriptor(nativeAbis = listOf("x86_64")),
            deviceApiLevel = 35,
            deviceSupportedAbis = listOf("arm64-v8a")
        )
        assertFalse(report.compatible)
        assertTrue(report.blockers.any { it.contains("CPU ABIs") })
    }

    @Test
    fun appWithoutNativeLibrariesGetsInformationalNote() {
        val report = AppCompatibilityEvaluator.evaluate(
            descriptor(nativeAbis = emptyList()),
            deviceApiLevel = 35,
            deviceSupportedAbis = listOf("arm64-v8a")
        )
        assertTrue(report.compatible)
        assertTrue(report.notes.any { it.contains("No native libraries") })
    }

    @Test
    fun reportsManifestCapabilitiesAndSplitApksAsNotes() {
        val report = AppCompatibilityEvaluator.evaluate(
            descriptor(
                apkPaths = listOf("base.apk", "config.arm64.apk"),
                requestedPermissions = listOf(AppPermissionDescriptor("android.permission.CAMERA", false)),
                activities = listOf(AppComponentDescriptor("Main", true, true)),
                services = listOf(AppComponentDescriptor("Sync", false, true))
            ),
            deviceApiLevel = 35,
            deviceSupportedAbis = listOf("arm64-v8a")
        )
        assertTrue(report.notes.any { it.contains("1 requested permissions") })
        assertTrue(report.notes.any { it.contains("1 activities, 1 services") })
        assertTrue(report.notes.any { it.contains("1 split APK") })
        assertTrue(report.notes.any { it.contains("do not guarantee") })
    }

    private fun descriptor(
        minSdk: Int = 21,
        launchActivity: String? = "com.example.app.MainActivity",
        nativeAbis: List<String> = emptyList(),
        apkPaths: List<String> = listOf("/data/app/example/base.apk"),
        requestedPermissions: List<AppPermissionDescriptor> = emptyList(),
        activities: List<AppComponentDescriptor> = emptyList(),
        services: List<AppComponentDescriptor> = emptyList()
    ) = AppPackageDescriptor(
        packageName = "com.example.app",
        appLabel = "Example",
        versionName = "1.0",
        versionCode = 1L,
        minSdk = minSdk,
        targetSdk = 35,
        launchActivity = launchActivity,
        apkPaths = apkPaths,
        nativeAbis = nativeAbis,
        requestedPermissions = requestedPermissions,
        activities = activities,
        services = services
    )
}
