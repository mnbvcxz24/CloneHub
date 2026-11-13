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
        assertEquals(1, report.notes.size)
        assertTrue(report.notes.single().contains("No native libraries"))
    }

    private fun descriptor(
        minSdk: Int = 21,
        launchActivity: String? = "com.example.app.MainActivity",
        nativeAbis: List<String> = emptyList()
    ) = AppPackageDescriptor(
        packageName = "com.example.app",
        appLabel = "Example",
        versionName = "1.0",
        versionCode = 1L,
        minSdk = minSdk,
        targetSdk = 35,
        launchActivity = launchActivity,
        apkPaths = listOf("/data/app/example/base.apk"),
        nativeAbis = nativeAbis
    )
}
