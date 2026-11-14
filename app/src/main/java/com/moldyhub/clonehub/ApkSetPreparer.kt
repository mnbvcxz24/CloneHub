package com.moldyhub.clonehub

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** A package-level snapshot of install APKs shared by all virtual instance profiles. */
data class PreparedApkSet(
    val packageName: String,
    val versionCode: Long,
    val directory: File,
    val apkFiles: List<File>,
    val totalBytes: Long
)

sealed class ApkPreparationResult {
    data class Success(val apkSet: PreparedApkSet, val alreadyPrepared: Boolean) : ApkPreparationResult()
    data class Failure(val reason: String) : ApkPreparationResult()
}

/**
 * Copies only the installed app's base APK and PackageManager-declared split APKs.
 * It never opens app data, expands ZIP entries, parses DEX, or reads app databases.
 * One package-level APK set is shared by all instances; per-instance copies are unnecessary.
 */
class ApkSetPreparer(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "prepared_apks")

    fun prepare(packageName: String): ApkPreparationResult {
        if (packageName.isBlank()) return ApkPreparationResult.Failure("Package name cannot be empty.")

        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getApplicationInfo(packageName, 0)
            }
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(packageName, 0)
            }

            val sourcePaths = buildList {
                appInfo.sourceDir?.takeIf { it.isNotBlank() }?.let(::add)
                appInfo.splitSourceDirs?.filter { it.isNotBlank() }?.let(::addAll)
            }.distinct()
            if (sourcePaths.isEmpty()) {
                return ApkPreparationResult.Failure("Android did not provide an installed base APK path.")
            }

            val sourceFiles = sourcePaths.map(::File)
            val unreadable = sourceFiles.firstOrNull { !it.isFile || !it.canRead() }
            if (unreadable != null) {
                return ApkPreparationResult.Failure("CloneHub cannot read installed APK file: ${unreadable.name}")
            }

            val versionCode = packageInfo.longVersionCode
            val packageDirectory = File(root, packageName)
            val targetDirectory = File(packageDirectory, versionCode.toString())
            val targetNames = sourceFiles.indices.map { index ->
                if (index == 0) "base.apk" else "split_${index}.apk"
            }

            if (isComplete(targetDirectory, targetNames)) {
                val files = targetNames.map { File(targetDirectory, it) }
                return ApkPreparationResult.Success(
                    PreparedApkSet(packageName, versionCode, targetDirectory, files, files.sumOf { it.length() }),
                    alreadyPrepared = true
                )
            }

            if (!packageDirectory.exists() && !packageDirectory.mkdirs()) {
                return ApkPreparationResult.Failure("Could not create CloneHub's APK storage directory.")
            }
            val stagingDirectory = File(packageDirectory, ".${versionCode}.staging")
            stagingDirectory.deleteRecursively()
            if (!stagingDirectory.mkdirs()) {
                return ApkPreparationResult.Failure("Could not create temporary APK preparation directory.")
            }

            try {
                val copiedFiles = sourceFiles.mapIndexed { index, source ->
                    val destination = File(stagingDirectory, targetNames[index])
                    BufferedInputStream(FileInputStream(source)).use { input ->
                        BufferedOutputStream(FileOutputStream(destination)).use { output ->
                            input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        }
                    }
                    check(destination.length() == source.length()) {
                        "APK copy size did not match source for ${source.name}."
                    }
                    destination
                }

                val totalBytes = copiedFiles.sumOf { it.length() }
                val metadata = JSONObject()
                    .put("packageName", packageName)
                    .put("versionCode", versionCode)
                    .put("versionName", packageInfo.versionName ?: "")
                    .put("apkCount", copiedFiles.size)
                    .put("totalBytes", totalBytes)
                    .put("files", JSONArray().apply {
                        copiedFiles.forEach { file ->
                            put(JSONObject().put("name", file.name).put("bytes", file.length()))
                        }
                    })
                File(stagingDirectory, "apk-set.json").writeText(metadata.toString())

                if (targetDirectory.exists() && !targetDirectory.deleteRecursively()) {
                    error("Could not replace the previous prepared APK set.")
                }
                check(stagingDirectory.renameTo(targetDirectory)) {
                    "Could not finalize the prepared APK set."
                }
                val finalFiles = targetNames.map { File(targetDirectory, it) }
                ApkPreparationResult.Success(
                    PreparedApkSet(packageName, versionCode, targetDirectory, finalFiles, totalBytes),
                    alreadyPrepared = false
                )
            } catch (error: Exception) {
                stagingDirectory.deleteRecursively()
                throw error
            }
        } catch (_: PackageManager.NameNotFoundException) {
            ApkPreparationResult.Failure("The selected app is no longer installed.")
        } catch (error: Exception) {
            ApkPreparationResult.Failure(error.message ?: "APK preparation failed.")
        }
    }

    private fun isComplete(directory: File, expectedNames: List<String>): Boolean {
        if (!directory.isDirectory || !File(directory, "apk-set.json").isFile) return false
        return expectedNames.all { name ->
            File(directory, name).let { it.isFile && it.length() > 0L }
        }
    }
}
