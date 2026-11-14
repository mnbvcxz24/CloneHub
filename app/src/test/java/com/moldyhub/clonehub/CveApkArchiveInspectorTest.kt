package com.moldyhub.clonehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CveApkArchiveInspectorTest {
    @Test
    fun scansApkArchiveEntriesAndNativeAbis() {
        val bytes = createArchive(
            "AndroidManifest.xml",
            "classes.dex",
            "classes2.dex",
            "lib/arm64-v8a/libsample.so",
            "lib/armeabi-v7a/libsample.so",
            "resources.arsc"
        )

        val result = CveApkArchiveInspector.inspect(ByteArrayInputStream(bytes))

        assertTrue(result.hasManifest)
        assertEquals(2, result.dexFileCount)
        assertEquals(6, result.entryCount)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), result.nativeAbis)
        assertEquals(bytes.size.toLong(), result.bytesRead)
        assertEquals(64, result.sha256.length)
    }

    @Test
    fun reportsArchiveWithoutManifest() {
        val result = CveApkArchiveInspector.inspect(
            ByteArrayInputStream(createArchive("assets/data.bin"))
        )
        assertFalse(result.hasManifest)
        assertEquals(0, result.dexFileCount)
        assertTrue(result.nativeAbis.isEmpty())
    }

    private fun createArchive(vararg names: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3, 4))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
