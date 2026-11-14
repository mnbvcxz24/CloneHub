package com.moldyhub.clonehub

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.zip.ZipFile

/** One manifest-declared component. This is metadata, not proof the component can run in CVE. */
data class AppComponentDescriptor(
    val name: String,
    val exported: Boolean,
    val enabled: Boolean,
    val permission: String? = null,
    val authorities: String? = null
)

data class AppPermissionDescriptor(
    val name: String,
    val grantedOnHost: Boolean
)

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
    val nativeAbis: List<String>,
    val requestedPermissions: List<AppPermissionDescriptor> = emptyList(),
    val activities: List<AppComponentDescriptor> = emptyList(),
    val services: List<AppComponentDescriptor> = emptyList(),
    val receivers: List<AppComponentDescriptor> = emptyList(),
    val providers: List<AppComponentDescriptor> = emptyList()
) {
    val hasLaunchActivity: Boolean get() = !launchActivity.isNullOrBlank()
    val hasNativeLibraries: Boolean get() = nativeAbis.isNotEmpty()
    val splitApkCount: Int get() = (apkPaths.size - 1).coerceAtLeast(0)
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

            val queryFlags = PackageManager.GET_PERMISSIONS or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(queryFlags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, queryFlags)
            }

            val launchActivity = packageManager.getLaunchIntentForPackage(packageName)
                ?.component?.className

            val apkPaths = buildList {
                if (appInfo.sourceDir.isNotBlank()) add(appInfo.sourceDir)
                appInfo.splitSourceDirs?.filter { it.isNotBlank() }?.let { addAll(it) }
            }.distinct()

            val permissionNames = packageInfo.requestedPermissions.orEmpty()
            val permissionFlags = packageInfo.requestedPermissionsFlags ?: intArrayOf()
            val permissions = permissionNames.mapIndexed { index, name ->
                AppPermissionDescriptor(
                    name = name,
                    grantedOnHost = ((permissionFlags.getOrNull(index) ?: 0) and
                        PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                )
            }

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
                    nativeAbis = findNativeAbis(apkPaths),
                    requestedPermissions = permissions,
                    activities = packageInfo.activities.orEmpty().map {
                        AppComponentDescriptor(it.name, it.exported, it.enabled, it.permission)
                    },
                    services = packageInfo.services.orEmpty().map {
                        AppComponentDescriptor(it.name, it.exported, it.enabled, it.permission)
                    },
                    receivers = packageInfo.receivers.orEmpty().map {
                        AppComponentDescriptor(it.name, it.exported, it.enabled, it.permission)
                    },
                    providers = packageInfo.providers.orEmpty().map {
                        AppComponentDescriptor(
                            it.name, it.exported, it.enabled, it.readPermission ?: it.writePermission,
                            it.authority
                        )
                    }
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
