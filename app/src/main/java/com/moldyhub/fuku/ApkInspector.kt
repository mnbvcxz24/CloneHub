package com.moldyhub.fuku

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ApkFileInspection(
    val fileName: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val componentCount: Int,
    val signerSha256: String?
)

data class WorkspaceInspection(
    val files: List<ApkFileInspection>,
    val packageIdsMatch: Boolean,
    val versionsMatch: Boolean,
    val signersMatch: Boolean
) {
    fun toDisplayText(): String {
        val first = files.first()
        val packageSuffix = first.packageName.take(180) + ".fuku"
        val signerStatus = when {
            files.any { it.signerSha256 == null } -> "Unavailable for one or more APKs"
            signersMatch -> "Consistent across APK files"
            else -> "MISMATCH — do not proceed"
        }
        return buildString {
            appendLine("APK ANALYSIS")
            appendLine("Source package: ${first.packageName}")
            appendLine("Version: ${first.versionName ?: "unknown"} (${first.versionCode})")
            appendLine("Android SDK: min ${first.minSdk}, target ${first.targetSdk}")
            appendLine("APK files: ${files.size}")
            appendLine("Package IDs match: ${if (packageIdsMatch) "Yes" else "NO"}")
            appendLine("Versions match: ${if (versionsMatch) "Yes" else "NO"}")
            appendLine("Signing certificates: $signerStatus")
            appendLine("Components in base APK: ${first.componentCount}")
            appendLine("Requested permissions: ${first.permissions.size}")
            first.permissions.take(12).forEach { appendLine(" • $it") }
            if (first.permissions.size > 12) appendLine(" • ... and ${first.permissions.size - 12} more")
            appendLine()
            appendLine("PROPOSED CLONE ID")
            appendLine(packageSuffix)
            appendLine()
            appendLine("STATUS: ANALYZED — NOT YET CLONED")
            append("The APKs have been inspected, but package/resource rewriting and re-signing are not part of this build yet. No clone was installed.")
        }
    }
}

object ApkInspector {
    @Suppress("DEPRECATION")
    fun inspect(packageManager: PackageManager, apkFiles: List<File>): WorkspaceInspection {
        require(apkFiles.isNotEmpty()) { "No APK files were found in this workspace." }
        val reports = apkFiles.map { file ->
            require(file.isFile && file.canRead()) { "APK is missing or unreadable: ${file.name}" }
            val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES
            val info = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                ?: error("Android could not read APK metadata: ${file.name}")
            inspectInfo(info, file)
        }
        val packagesMatch = reports.map { it.packageName }.distinct().size == 1
        val versionsMatch = reports.map { it.versionCode }.distinct().size == 1
        val signerValues = reports.map { it.signerSha256 }
        val signersMatch = signerValues.none { it == null } && signerValues.distinct().size == 1
        return WorkspaceInspection(reports, packagesMatch, versionsMatch, signersMatch)
    }

    @Suppress("DEPRECATION")
    private fun inspectInfo(info: PackageInfo, file: File): ApkFileInspection {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        val signer = signatures?.firstOrNull()?.toByteArray()?.let { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate)
                .joinToString(":") { byte -> "%02X".format(byte) }
        }
        val appInfo = info.applicationInfo
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return ApkFileInspection(
            fileName = file.name,
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = code,
            minSdk = appInfo?.minSdkVersion ?: 0,
            targetSdk = appInfo?.targetSdkVersion ?: 0,
            permissions = info.requestedPermissions?.toList() ?: emptyList(),
            componentCount = (info.activities?.size ?: 0) +
                (info.services?.size ?: 0) +
                (info.receivers?.size ?: 0) +
                (info.providers?.size ?: 0),
            signerSha256 = signer
        )
    }
}
