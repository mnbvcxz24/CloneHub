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
    val totalBytes: Long,
    val inspection: ApkSetInspection
)

sealed class ApkPreparationResult {
    data class Success(val apkSet: PreparedApkSet, val alreadyPrepared: Boolean) : ApkPreparationResult()
    data class Failure(val reason: String) : ApkPreparationResult()
}

/**
 * Copies Android's installed base/split APK set, then validates each ZIP/APK container,
 * records SHA-256 digests and checks native ABI compatibility. It never reads app data,
 * extracts APK entries, parses DEX, or claims to launch the target app.
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

            val splitNames = packageInfo.splitNames?.toList().orEmpty()
            val splitPaths = appInfo.splitSourceDirs?.filter { it.isNotBlank() }.orEmpty()
            if (splitNames.size != splitPaths.size) {
                return ApkPreparationResult.Failure(
                    "Android returned inconsistent split metadata: ${splitNames.size} split name(s), ${splitPaths.size} split file(s)."
                )
            }

            val sourcePaths = buildList {
                appInfo.sourceDir?.takeIf { it.isNotBlank() }?.let(::add)
                addAll(splitPaths)
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
                val inspection = inspect(files)
                    ?: return ApkPreparationResult.Failure("The previously prepared APK set failed integrity validation. Prepare it again.")
                return ApkPreparationResult.Success(
                    PreparedApkSet(packageName, versionCode, targetDirectory, files, files.sumOf { it.length() }, inspection),
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

                val inspection = inspect(copiedFiles)
                    ?: error("APK validation failed: one or more files are not valid APK containers.")
                val manifest = (ApkManifestInspector.inspect(appContext.packageManager, copiedFiles.first())
                    as? ApkManifestInspectionResult.Success)?.report
                    ?: error("Android could not read the copied base APK manifest.")
                check(manifest.packageName == packageName) {
                    "The copied APK manifest package does not match the selected app."
                }
                check(manifest.versionCode == versionCode) {
                    "The copied APK manifest version does not match the selected app."
                }
                val totalBytes = copiedFiles.sumOf { it.length() }
                val metadata = JSONObject()
                    .put("packageName", packageName)
                    .put("versionCode", versionCode)
                    .put("versionName", packageInfo.versionName ?: "")
                    .put("manifestMinSdk", manifest.minSdkVersion)
                    .put("manifestTargetSdk", manifest.targetSdkVersion)
                    .put("manifestApplicationClass", manifest.applicationClassName ?: "")
                    .put("manifestActivities", JSONArray().apply { manifest.declaredActivities.forEach { put(it) } })
                    .put("manifestExportedActivities", JSONArray().apply { manifest.exportedActivities.forEach { put(it) } })
                    .put("splitNames", JSONArray().apply { splitNames.forEach { put(it) } })
                    .put("apkCount", copiedFiles.size)
                    .put("totalBytes", totalBytes)
                    .put("nativeAbis", JSONArray().apply { inspection.nativeAbis.forEach { put(it) } })
                    .put("supportedAbis", JSONArray().apply { inspection.supportedAbis.forEach { put(it) } })
                    .put("hasNativeLibraries", inspection.hasNativeLibraries)
                    .put("deviceAbiCompatible", inspection.deviceAbiCompatible)
                    .put("files", JSONArray().apply {
                        inspection.reports.forEach { report ->
                            put(JSONObject()
                                .put("name", report.fileName)
                                .put("bytes", report.sizeBytes)
                                .put("sha256", report.sha256)
                                .put("nativeAbis", JSONArray().apply { report.nativeAbis.forEach { put(it) } }))
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
                    PreparedApkSet(packageName, versionCode, targetDirectory, finalFiles, totalBytes, inspection),
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

    private fun inspect(files: List<File>): ApkSetInspection? =
        (ApkArchiveInspector.inspect(files, Build.SUPPORTED_ABIS.toList()) as? ApkInspectionResult.Success)?.inspection

    private fun isComplete(directory: File, expectedNames: List<String>): Boolean {
        val metadataFile = File(directory, "apk-set.json")
        if (!directory.isDirectory || !metadataFile.isFile) return false
        val files = expectedNames.map { File(directory, it) }
        val hasManifestMetadata = runCatching {
            JSONObject(metadataFile.readText()).has("manifestMinSdk")
        }.getOrDefault(false)
        return hasManifestMetadata && expectedNames.all { name ->
            File(directory, name).let { it.isFile && it.length() > 0L }
        } && inspect(files) != null
    }
}
