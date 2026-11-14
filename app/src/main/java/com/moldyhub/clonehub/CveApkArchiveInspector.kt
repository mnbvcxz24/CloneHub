package com.moldyhub.clonehub

import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Read-only archive scan performed inside the isolated worker process. */
data class CveApkInspectionReport(
    val sha256: String,
    val bytesRead: Long,
    val entryCount: Int,
    val hasManifest: Boolean,
    val dexFileCount: Int,
    val nativeAbis: List<String>
)

object CveApkArchiveInspector {
    fun inspect(input: InputStream): CveApkInspectionReport {
        val digest = MessageDigest.getInstance("SHA-256")
        val digestInput = DigestInputStream(input, digest)
        var entryCount = 0
        var hasManifest = false
        var dexFileCount = 0
        val abis = linkedSetOf<String>()
        val buffer = ByteArray(16 * 1024)

        ZipInputStream(digestInput).use { zip ->
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
                    // Drain each entry so the digest covers the complete archive stream.
                }
                zip.closeEntry()
            }
        }

        val bytesRead = digestInput.byteCount
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return CveApkInspectionReport(
            sha256 = sha256,
            bytesRead = bytesRead,
            entryCount = entryCount,
            hasManifest = hasManifest,
            dexFileCount = dexFileCount,
            nativeAbis = abis.sorted()
        )
    }
}
