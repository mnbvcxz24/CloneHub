package com.moldyhub.clonehub

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** Metadata parsed by Android's public APK archive parser from the base APK manifest. */
data class ApkManifestReport(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val applicationClassName: String?,
    val declaredActivities: List<String>,
    val exportedActivities: List<String>
)

sealed class ApkManifestInspectionResult {
    data class Success(val report: ApkManifestReport) : ApkManifestInspectionResult()
    data class Failure(val reason: String) : ApkManifestInspectionResult()
}

/**
 * Reads package metadata from an APK archive using PackageManager's public API.
 * This does not install the APK, load its DEX, resolve runtime dependencies, or launch it.
 */
object ApkManifestInspector {
    fun inspect(packageManager: PackageManager, baseApk: File): ApkManifestInspectionResult {
        if (!baseApk.isFile || !baseApk.canRead() || baseApk.length() <= 0L) {
            return ApkManifestInspectionResult.Failure("The base APK is missing, empty, or unreadable.")
        }

        return try {
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
            val parsed: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(
                    baseApk.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(baseApk.absolutePath, flags)
            }
            val info = parsed ?: return ApkManifestInspectionResult.Failure(
                "Android could not parse the base APK manifest."
            )
            val app = info.applicationInfo
                ?: return ApkManifestInspectionResult.Failure("The APK manifest has no application declaration.")
            val activities = info.activities.orEmpty()
            val report = ApkManifestReport(
                packageName = info.packageName,
                versionCode = info.longVersionCode,
                versionName = info.versionName,
                minSdkVersion = app.minSdkVersion,
                targetSdkVersion = app.targetSdkVersion,
                applicationClassName = app.className,
                declaredActivities = activities.map { it.name }.distinct(),
                exportedActivities = activities.filter { it.exported }.map { it.name }.distinct()
            )
            ApkManifestInspectionResult.Success(report)
        } catch (error: Exception) {
            ApkManifestInspectionResult.Failure(
                error.message ?: "Android failed to inspect the APK manifest."
            )
        }
    }
}
