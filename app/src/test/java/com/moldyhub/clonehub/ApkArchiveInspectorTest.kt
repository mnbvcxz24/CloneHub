package com.moldyhub.clonehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkArchiveInspectorTest {
    @Test
    fun validatesApkContainerAndReportsNativeAbiCompatibility() {
        val root = Files.createTempDirectory("clonehub-apk-inspect").toFile()
        try {
            val apk = File(root, "base.apk")
            writeApk(apk, listOf("lib/arm64-v8a/libsample.so", "classes.dex"))
            val result = ApkArchiveInspector.inspect(listOf(apk), listOf("arm64-v8a", "armeabi-v7a"))

            assertTrue(result is ApkInspectionResult.Success)
            val inspection = (result as ApkInspectionResult.Success).inspection
            assertTrue(inspection.deviceAbiCompatible)
            assertEquals(setOf("arm64-v8a"), inspection.nativeAbis)
            assertEquals(64, inspection.reports.single().sha256.length)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsIncompatibleNativeAbiWithoutCallingArchiveCorrupt() {
        val root = Files.createTempDirectory("clonehub-apk-inspect").toFile()
        try {
            val apk = File(root, "base.apk")
            writeApk(apk, listOf("lib/x86/libsample.so"))
            val result = ApkArchiveInspector.inspect(listOf(apk), listOf("arm64-v8a"))

            assertTrue(result is ApkInspectionResult.Success)
            assertFalse((result as ApkInspectionResult.Success).inspection.deviceAbiCompatible)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipWithoutAndroidManifest() {
        val root = Files.createTempDirectory("clonehub-apk-inspect").toFile()
        try {
            val apk = File(root, "not-an-apk.apk")
            writeApk(apk, listOf("classes.dex"), includeManifest = false)
            val result = ApkArchiveInspector.inspect(listOf(apk), listOf("arm64-v8a"))
            assertTrue(result is ApkInspectionResult.Failure)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeApk(file: File, entries: List<String>, includeManifest: Boolean = true) {
        ZipOutputStream(file.outputStream()).use { zip ->
            ((if (includeManifest) listOf("AndroidManifest.xml") else emptyList()) + entries).forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }
}
