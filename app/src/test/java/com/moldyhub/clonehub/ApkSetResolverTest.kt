package com.moldyhub.clonehub

import com.moldyhub.clonehub.cve.VirtualAppConfiguration
import com.moldyhub.clonehub.cve.VirtualAppInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ApkSetResolverTest {
    @Test
    fun resolvesBaseAndSplitApksForRecordedVersion() {
        val root = Files.createTempDirectory("clonehub-apks").toFile()
        try {
            val directory = File(root, "com.example.game/42").apply { mkdirs() }
            File(directory, "apk-set.json").writeText("{}")
            File(directory, "base.apk").writeBytes(byteArrayOf(1, 2, 3))
            File(directory, "split_1.apk").writeBytes(byteArrayOf(4, 5))
            File(directory, "split_2.apk").writeBytes(byteArrayOf(6))

            val instance = VirtualAppInstance.create(
                "com.example.game",
                VirtualAppConfiguration(displayName = "Instance 1", apkSetVersionCode = 42L)
            )
            val result = ApkSetResolver(root).resolve(instance)

            assertTrue(result is ApkSetResolution.Success)
            val set = (result as ApkSetResolution.Success).apkSet
            assertEquals(listOf("base.apk", "split_1.apk", "split_2.apk"), set.apkFiles.map { it.name })
            assertEquals(6L, set.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failsWhenVersionWasNotRecorded() {
        val root = Files.createTempDirectory("clonehub-apks").toFile()
        try {
            val instance = VirtualAppInstance.create("com.example.game")
            val result = ApkSetResolver(root).resolve(instance)
            assertTrue(result is ApkSetResolution.Failure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failsWhenPreparedBaseApkIsMissing() {
        val root = Files.createTempDirectory("clonehub-apks").toFile()
        try {
            val directory = File(root, "com.example.game/42").apply { mkdirs() }
            File(directory, "apk-set.json").writeText("{}")
            val instance = VirtualAppInstance.create(
                "com.example.game",
                VirtualAppConfiguration(apkSetVersionCode = 42L)
            )
            val result = ApkSetResolver(root).resolve(instance)
            assertTrue(result is ApkSetResolution.Failure)
        } finally {
            root.deleteRecursively()
        }
    }
}
