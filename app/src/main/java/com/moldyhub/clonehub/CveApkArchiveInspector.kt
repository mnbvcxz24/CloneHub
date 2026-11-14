package com.moldyhub.clonehub

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class CveApkInspectionReport(
    val sha256: String,
    val bytesRead: Long,
    val entryCount: Int,
    val hasManifest: Boolean,
    val dexFileCount: Int,
    val nativeAbis: List<String>
)

object CveApkArchiveInspector {
    fun inspect(
        input: InputStream,
        tempDirectory: File = File(System.getProperty("java.io.tmpdir") ?: ".")
    ): CveApkInspectionReport {
        require(tempDirectory.exists() || tempDirectory.mkdirs()) {
            "Worker temporary directory is unavailable."
        }
        val tempApk = File.createTempFile("clonehub-apk-", ".tmp", tempDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            tempApk.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    bytesRead += count
                }
            }

            var entryCount = 0
            var hasManifest = false
            var dexFileCount = 0
            val abis = linkedSetOf<String>()
            ZipFile(tempApk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    entryCount++
                    when {
                        name == "AndroidManifest.xml" -> hasManifest = true
                        name.matches(Regex("classes(\\d*)\\.dex")) -> dexFileCount++
                        name.startsWith("lib/") -> {
                            val parts = name.split('/')
                            if (parts.size >= 3 && parts[1].isNotBlank()) abis.add(parts[1])
                        }
                    }
                }
            }

            return CveApkInspectionReport(
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                bytesRead = bytesRead,
                entryCount = entryCount,
                hasManifest = hasManifest,
                dexFileCount = dexFileCount,
                nativeAbis = abis.sorted()
            )
        } finally {
            tempApk.delete()
        }
    }
}
