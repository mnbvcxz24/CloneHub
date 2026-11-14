package com.moldyhub.clonehub

import com.moldyhub.clonehub.cve.VirtualAppInstance
import java.io.File

/** A validated view of the package-level APK snapshot used by an instance. */
data class ResolvedApkSet(
    val packageName: String,
    val versionCode: Long,
    val directory: File,
    val apkFiles: List<File>,
    val totalBytes: Long
)

sealed class ApkSetResolution {
    data class Success(val apkSet: ResolvedApkSet) : ApkSetResolution()
    data class Failure(val reason: String) : ApkSetResolution()
}

/**
 * Resolves the shared APK set recorded by an instance profile.
 * It checks only CloneHub-owned file names and sizes; it never opens APK contents.
 */
class ApkSetResolver(private val preparedApksRoot: File) {
    fun resolve(instance: VirtualAppInstance): ApkSetResolution {
        val versionCode = instance.configuration.apkSetVersionCode
            ?: return ApkSetResolution.Failure("This instance has no prepared APK version recorded. Prepare its APK set again.")

        if (versionCode <= 0L || instance.packageName.isBlank() ||
            instance.packageName == "." || instance.packageName == ".." ||
            '/' in instance.packageName || '\\' in instance.packageName
        ) {
            return ApkSetResolution.Failure("The instance APK reference is invalid.")
        }

        return try {
            val root = preparedApksRoot.canonicalFile
            val directory = File(File(root, instance.packageName), versionCode.toString()).canonicalFile
            if (!directory.path.startsWith(root.path + File.separator)) {
                return ApkSetResolution.Failure("The prepared APK path is outside CloneHub storage.")
            }
            if (!directory.isDirectory || !File(directory, "apk-set.json").isFile) {
                return ApkSetResolution.Failure("Prepared APK set not found for version " + versionCode + ". Prepare the app again from My Library.")
            }

            val base = File(directory, "base.apk")
            if (!base.isFile || base.length() <= 0L || base.canonicalFile.parentFile != directory) {
                return ApkSetResolution.Failure("The prepared base APK is missing or invalid.")
            }

            val splitFiles = directory.listFiles()
                ?.filter { it.isFile && SPLIT_NAME.matches(it.name) }
                ?.sortedBy { it.name.substringAfter("split_").substringBefore(".apk").toIntOrNull() ?: Int.MAX_VALUE }
                ?: emptyList()
            if (splitFiles.any { it.length() <= 0L || it.canonicalFile.parentFile != directory }) {
                return ApkSetResolution.Failure("A prepared split APK is missing or invalid.")
            }

            val files = listOf(base) + splitFiles
            ApkSetResolution.Success(
                ResolvedApkSet(
                    instance.packageName,
                    versionCode,
                    directory,
                    files,
                    files.sumOf { it.length() }
                )
            )
        } catch (error: Exception) {
            ApkSetResolution.Failure(error.message ?: "Could not resolve the prepared APK set.")
        }
    }

    private companion object {
        val SPLIT_NAME = Regex("split_[0-9]+\\.apk")
    }
}
