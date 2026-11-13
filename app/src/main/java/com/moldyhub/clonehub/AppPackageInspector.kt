package com.moldyhub.clonehub

import android.content.pm.PackageManager
import android.os.Build
import java.util.zip.ZipFile

/** Read-only metadata about an already-installed app. This does not launch or clone it. */
data class AppPackageDescriptor(
    val packageName: String,
    val appLabel: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val launchActivity: String?,
    val apkPaths: List<String>,
    val nativeAbis: List<String>
) {
    val hasLaunchActivity: Boolean get() = !launchActivity.isNullOrBlank()
    val hasNativeLibraries: Boolean get() = nativeAbis.isNotEmpty()
}

sealed class AppPackageInspectionResult {
    data class Success(val descriptor: AppPackageDescriptor) : AppPackageInspectionResult()
    data class NotFound(val packageName: String) : AppPackageInspectionResult()
    data class Failed(val packageName: String, val reason: String) : AppPackageInspectionResult()
}

/**
 * Uses Android PackageManager to inspect an installed package and its APK/split APK files.
 * Package visibility rules still apply; callers should inspect packages discovered through
 * CloneHub's visible installed-app list.
 */
class AppPackageInspector(private val packageManager: PackageManager) {

    fun inspect(packageName: String): AppPackageInspectionResult {
        if (packageName.isBlank()) {
            return AppPackageInspectionResult.Failed(packageName, "Package name cannot be empty.")
        }

        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }

            val launchActivity = packageManager.getLaunchIntentForPackage(packageName)
                ?.component?.className

            val apkPaths = buildList {
                if (appInfo.sourceDir.isNotBlank()) add(appInfo.sourceDir)
                appInfo.splitSourceDirs?.filter { it.isNotBlank() }?.let(::addAll)
            }.distinct()

            AppPackageInspectionResult.Success(
                AppPackageDescriptor(
                    packageName = packageInfo.packageName,
                    appLabel = packageManager.getApplicationLabel(appInfo).toString(),
                    versionName = packageInfo.versionName,
                    versionCode = packageInfo.longVersionCode,
                    minSdk = appInfo.minSdkVersion,
                    targetSdk = appInfo.targetSdkVersion,
                    launchActivity = launchActivity,
                    apkPaths = apkPaths,
                    nativeAbis = findNativeAbis(apkPaths)
                )
            )
        } catch (_: PackageManager.NameNotFoundException) {
            AppPackageInspectionResult.NotFound(packageName)
        } catch (error: Exception) {
            AppPackageInspectionResult.Failed(
                packageName,
                error.message ?: "Package inspection failed."
            )
        }
    }

    private fun findNativeAbis(apkPaths: List<String>): List<String> {
        val abis = linkedSetOf<String>()
        apkPaths.forEach { path ->
            try {
                ZipFile(path).use { apk ->
                    val entries = apk.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (!name.startsWith("lib/")) continue
                        val parts = name.split('/')
                        if (parts.size >= 3 && parts[1].isNotBlank()) abis.add(parts[1])
                    }
                }
            } catch (_: Exception) {
                // An unreadable split should not hide metadata from the remaining APKs.
            }
        }
        return abis.sorted()
    }
}
