package com.moldyhub.clonehub

/** Device-side checks that can be performed before attempting runtime integration. */
data class AppCompatibilityReport(
    val compatible: Boolean,
    val blockers: List<String>,
    val notes: List<String>
)

object AppCompatibilityEvaluator {
    fun evaluate(
        app: AppPackageDescriptor,
        deviceApiLevel: Int,
        deviceSupportedAbis: List<String>
    ): AppCompatibilityReport {
        val blockers = mutableListOf<String>()
        val notes = mutableListOf<String>()

        if (app.minSdk > deviceApiLevel) {
            blockers += "Requires Android API ${app.minSdk}, but this device is API $deviceApiLevel."
        }
        if (!app.hasLaunchActivity) {
            blockers += "No launch activity was found for this package."
        }
        if (app.nativeAbis.isNotEmpty() &&
            deviceSupportedAbis.none { it in app.nativeAbis }
        ) {
            blockers += "The app's native libraries do not match this device's supported CPU ABIs."
        }
        if (app.nativeAbis.isEmpty()) {
            notes += "No native libraries were detected in the APK files."
        }
        if (app.apkPaths.isEmpty()) {
            notes += "APK source paths were not exposed by PackageManager."
        }
        notes += "Found ${app.requestedPermissions.size} requested permissions; host grant state is informational only."
        notes += "Manifest components: ${app.activities.size} activities, ${app.services.size} services, " +
            "${app.receivers.size} receivers, ${app.providers.size} providers."
        if (app.splitApkCount > 0) {
            notes += "The installed app uses ${app.splitApkCount} split APK file(s)."
        }
        notes += "Preliminary device checks do not guarantee that this app can execute inside CloneHub."

        return AppCompatibilityReport(
            compatible = blockers.isEmpty(),
            blockers = blockers,
            notes = notes
        )
    }
}
