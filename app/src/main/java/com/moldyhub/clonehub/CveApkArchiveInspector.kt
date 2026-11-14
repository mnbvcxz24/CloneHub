package com.moldyhub.clonehub

import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class CveApkInspectionReport(
    val sha256: String,
    val bytesRead: Long,
    val entryCount: Int,
    val hasManifest: Boolean,
    val dexFileCount: Int,
    val nativeAbis: List<String>
)

object CveApkArchiveInspector {
    /** Scans a seek-free APK descriptor stream without writing into app-private storage. */
    fun inspect(input: InputStream): CveApkInspectionReport {
        val counter = CountingInputStream(input)
        val digest = MessageDigest.getInstance("SHA-256")
        val digestInput = DigestInputStream(counter, digest)
        val zip = ZipInputStream(digestInput)
        var entryCount = 0
        var hasManifest = false
        var dexFileCount = 0
        val abis = linkedSetOf<String>()
        val buffer = ByteArray(32 * 1024)

        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                when {
                    entry.name == "AndroidManifest.xml" -> hasManifest = true
                    entry.name.matches(Regex("classes(\\d*)\\.dex")) -> dexFileCount++
                    entry.name.startsWith("lib/") -> {
                        val parts = entry.name.split('/')
                        if (parts.size >= 3 && parts[1].isNotBlank()) abis.add(parts[1])
                    }
                }
                while (zip.read(buffer) != -1) {
                    // Drain each compressed entry; no APK code is loaded or executed.
                }
                zip.closeEntry()
            }

            // ZipInputStream stops at the central directory. Drain the remaining raw
            // bytes so the digest and byte count cover the complete APK file.
            while (digestInput.read(buffer) != -1) {
                // Hash remaining ZIP directory and end-of-central-directory records.
            }
        } finally {
            runCatching { zip.close() }
        }

        return CveApkInspectionReport(
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            bytesRead = counter.bytesRead,
            entryCount = entryCount,
            hasManifest = hasManifest,
            dexFileCount = dexFileCount,
            nativeAbis = abis.sorted()
        )
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) bytesRead++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) bytesRead += count
            return count
        }
    }
}
