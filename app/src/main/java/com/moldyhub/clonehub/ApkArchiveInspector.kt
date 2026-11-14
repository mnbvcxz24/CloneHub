package com.moldyhub.clonehub

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Inspects APK containers without extracting entries, loading DEX, or touching app data. */
data class ApkArchiveReport(val fileName: String, val sizeBytes: Long, val sha256: String, val nativeAbis: Set<String>)
data class ApkSetInspection(
    val reports: List<ApkArchiveReport>,
    val nativeAbis: Set<String>,
    val supportedAbis: List<String>,
    val hasNativeLibraries: Boolean,
    val deviceAbiCompatible: Boolean
)
sealed class ApkInspectionResult {
    data class Success(val inspection: ApkSetInspection) : ApkInspectionResult()
    data class Failure(val reason: String) : ApkInspectionResult()
}

object ApkArchiveInspector {
    private val knownAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "riscv64")

    fun inspect(apkFiles: List<File>, supportedAbis: List<String>): ApkInspectionResult {
        if (apkFiles.isEmpty()) return ApkInspectionResult.Failure("The APK set is empty.")
        if (supportedAbis.isEmpty()) return ApkInspectionResult.Failure("Android did not report any supported CPU ABIs.")
        return try {
            val reports = apkFiles.map { file ->
                if (!file.isFile || file.length() <= 0L || !file.canRead()) {
                    throw IllegalArgumentException("APK file is missing or unreadable: ${file.name}")
                }
                val nativeAbis = linkedSetOf<String>()
                ZipFile(file).use { zip ->
                    val manifest = zip.getEntry("AndroidManifest.xml")
                    if (manifest == null || manifest.isDirectory || manifest.size <= 0L) {
                        throw IllegalArgumentException("${file.name} is not a valid APK container: AndroidManifest.xml is missing.")
                    }
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (name.startsWith("lib/")) {
                            val parts = name.split('/')
                            if (parts.size == 3 && parts[1] in knownAbis && parts[2].endsWith(".so")) nativeAbis.add(parts[1])
                        }
                    }
                }
                ApkArchiveReport(file.name, file.length(), sha256(file), nativeAbis)
            }
            val allNativeAbis = reports.flatMapTo(linkedSetOf()) { it.nativeAbis }
            val hasNative = reports.any { it.nativeAbis.isNotEmpty() }
            ApkInspectionResult.Success(
                ApkSetInspection(reports, allNativeAbis, supportedAbis, hasNative, !hasNative || allNativeAbis.any { it in supportedAbis })
            )
        } catch (error: Exception) {
            ApkInspectionResult.Failure(error.message ?: "Could not inspect the APK archive set.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
