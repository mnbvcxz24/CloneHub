package com.moldyhub.clonehub

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * CloneHub installed-app library and BlackBox virtual-instance manager.
 * Apps are sourced from the user's installed launcher apps; no APK picker is used.
 */
class MainActivity : Activity() {

    private lateinit var libraryContainer: LinearLayout
    private lateinit var instancesContainer: LinearLayout
    private lateinit var installedContainer: LinearLayout
    private lateinit var subtitleView: TextView

    private val preferences by lazy {
        getSharedPreferences("clonehub_library", MODE_PRIVATE)
    }

    private val pendingInstanceKeys = mutableSetOf<String>()
    private val instanceErrors = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsLogger.installCrashHandler(this)
        DiagnosticsLogger.record(
            this,
            "onCreate; SDK=${Build.VERSION.SDK_INT}; device=${Build.MANUFACTURER} ${Build.MODEL}; app=$packageName"
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(0xFFF7F7FA.toInt())
        }

        val title = TextView(this).apply {
            text = "CloneHub"
            textSize = 28f
            setTextColor(0xFF17171C.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        subtitleView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF666672.toInt())
            setPadding(0, dp(4), 0, dp(12))
        }

        val diagnosticsButton = Button(this).apply {
            text = "Save diagnostics file"
            isAllCaps = false
            setOnClickListener { exportDiagnostics() }
        }

        val libraryTitle = sectionTitle("My library")
        libraryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val instancesTitle = sectionTitle("Instances")
        instancesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val installedTitle = sectionTitle("Installed apps")
        installedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(libraryTitle)
            addView(libraryContainer)
            addView(instancesTitle)
            addView(instancesContainer)
            addView(installedTitle)
            addView(installedContainer)
        }

        val scrollView = ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(title)
        root.addView(subtitleView)
        root.addView(diagnosticsButton)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(8) }
        )

        setContentView(root)
        updateEngineStatus()
        renderLibrary()
        renderInstances()
        renderInstalledApps()
    }

    private fun updateEngineStatus() {
        subtitleView.text = "Choose installed apps • ${BlackBoxRuntime.statusText()}"
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setTextColor(0xFF17171C.toInt())
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(10))
    }

    private fun getInstalledUserApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                val appPackage = appInfo.packageName
                if (appPackage == packageName) return@mapNotNull null

                val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                if ((appInfo.flags and systemFlags) != 0) return@mapNotNull null

                try {
                    InstalledApp(
                        packageName = appPackage,
                        label = packageManager.getApplicationLabel(appInfo).toString(),
                        icon = packageManager.getApplicationIcon(appInfo)
                    )
                } catch (error: Exception) {
                    DiagnosticsLogger.record(
                        this,
                        "Skipping installed app $appPackage: ${error.javaClass.simpleName}: ${error.message}"
                    )
                    null
                }
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun renderInstalledApps() {
        installedContainer.removeAllViews()
        val apps = getInstalledUserApps()

        if (apps.isEmpty()) {
            installedContainer.addView(emptyMessage("No user-installed apps were found."))
            return
        }

        apps.forEach { app ->
            val alreadyAdded = getLibraryPackages().contains(app.packageName)
            val row = appRow(
                app = app,
                actionLabel = if (alreadyAdded) "Added" else "Add",
                onAction = {
                    if (!alreadyAdded) {
                        val packages = getLibraryPackages().toMutableSet()
                        packages.add(app.packageName)
                        saveLibraryPackages(packages)
                        renderLibrary()
                        renderInstalledApps()
                        Toast.makeText(this, "${app.label} added to CloneHub", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            installedContainer.addView(row, rowLayoutParams())
        }
    }

    private fun renderLibrary() {
        libraryContainer.removeAllViews()
        val packages = getLibraryPackages().toList().sortedBy { getAppLabel(it).lowercase() }

        if (packages.isEmpty()) {
            libraryContainer.addView(emptyMessage("No apps added yet. Choose an app below."))
            return
        }

        packages.forEach { appPackage ->
            val app = getInstalledApp(appPackage)
            if (app == null) {
                val updated = getLibraryPackages().toMutableSet()
                updated.remove(appPackage)
                saveLibraryPackages(updated)
            } else {
                val row = appRow(
                    app = app,
                    actionLabel = "Remove",
                    onAction = { removeLibraryApp(app) },
                    secondaryActionLabel = "Add instance",
                    onSecondaryAction = { addInstance(app) }
                )
                libraryContainer.addView(row, rowLayoutParams())
            }
        }
    }

    private fun renderInstances() {
        instancesContainer.removeAllViews()
        var instanceCount = 0
        getLibraryPackages().toList().sortedBy { getAppLabel(it).lowercase() }.forEach { appPackage ->
            val app = getInstalledApp(appPackage) ?: return@forEach
            getInstanceLabels(appPackage)
                .sortedBy { it.substringAfterLast(" ").toIntOrNull() ?: 0 }
                .forEach { instanceLabel ->
                    instanceCount++
                    val key = instancePreferenceKey(appPackage, instanceLabel)
                    val userId = preferences.getInt(userIdKey(key), -1)
                    val ready = preferences.getBoolean(readyKey(key), false)
                    val pending = pendingInstanceKeys.contains(key)
                    val error = instanceErrors[key]

                    val status = when {
                        pending -> "Installing into isolated virtual user..."
                        error != null -> "Setup failed: $error"
                        ready && userId > 0 -> "Installed in isolated user #$userId"
                        userId > 0 -> "Virtual user #$userId • setup incomplete"
                        else -> "Not installed in virtual runtime yet"
                    }

                    val actionLabel = when {
                        pending -> "Working"
                        ready -> "Launch"
                        userId > 0 -> "Retry"
                        else -> "Set up"
                    }

                    instancesContainer.addView(
                        instanceRow(
                            app = app,
                            instanceLabel = instanceLabel,
                            status = status,
                            actionLabel = actionLabel,
                            actionEnabled = !pending,
                            onAction = {
                                if (ready && userId > 0) launchInstance(app, instanceLabel)
                                else connectInstance(app, instanceLabel)
                            },
                            onRemove = { removeInstance(app, instanceLabel) }
                        ),
                        rowLayoutParams()
                    )
                }
        }

        if (instanceCount == 0) {
            instancesContainer.addView(
                emptyMessage("No instances yet. Add an instance from an app in My library.")
            )
        }
    }

    private fun addInstance(app: InstalledApp) {
        val labels = getInstanceLabels(app.packageName).toMutableSet()
        var nextNumber = 1
        while (labels.contains("Instance $nextNumber")) nextNumber++
        val instanceLabel = "Instance $nextNumber"
        labels.add(instanceLabel)
        saveInstanceLabels(app.packageName, labels)
        DiagnosticsLogger.record(this, "Created virtual instance request $instanceLabel for ${app.packageName}")
        renderInstances()
        connectInstance(app, instanceLabel)
    }

    private fun connectInstance(app: InstalledApp, instanceLabel: String) {
        val key = instancePreferenceKey(app.packageName, instanceLabel)
        if (!pendingInstanceKeys.add(key)) return
        instanceErrors.remove(key)
        renderInstances()

        Thread {
            var userId = preferences.getInt(userIdKey(key), -1)
            try {
                if (userId <= 0) {
                    userId = BlackBoxRuntime.allocateUserId(this)
                    preferences.edit().putInt(userIdKey(key), userId).apply()
                }

                BlackBoxRuntime.installApp(app.packageName, userId)
                preferences.edit().putBoolean(readyKey(key), true).apply()
                DiagnosticsLogger.record(
                    this,
                    "Virtual install succeeded: package=${app.packageName}, instance=$instanceLabel, userId=$userId"
                )
                runOnUiThread {
                    pendingInstanceKeys.remove(key)
                    renderInstances()
                    Toast.makeText(this, "$instanceLabel is ready in the virtual runtime.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                if (userId > 0) runCatching { BlackBoxRuntime.deleteUser(userId) }
                preferences.edit()
                    .remove(userIdKey(key))
                    .remove(readyKey(key))
                    .apply()
                val message = error.message ?: error.javaClass.simpleName
                DiagnosticsLogger.record(
                    this,
                    "Virtual install failed: package=${app.packageName}, instance=$instanceLabel, error=${error.javaClass.name}: $message"
                )
                runOnUiThread {
                    pendingInstanceKeys.remove(key)
                    instanceErrors[key] = message.take(90)
                    renderInstances()
                    updateEngineStatus()
                    Toast.makeText(this, "Could not set up $instanceLabel: $message", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchInstance(app: InstalledApp, instanceLabel: String) {
        val key = instancePreferenceKey(app.packageName, instanceLabel)
        val userId = preferences.getInt(userIdKey(key), -1)
        if (userId <= 0) {
            connectInstance(app, instanceLabel)
            return
        }

        try {
            DiagnosticsLogger.record(
                this,
                "Launching virtual app: package=${app.packageName}, instance=$instanceLabel, userId=$userId"
            )
            val launched = BlackBoxRuntime.launchApp(app.packageName, userId)
            if (!launched) {
                Toast.makeText(this, "BlackBox could not launch this app instance.", Toast.LENGTH_LONG).show()
            }
        } catch (error: Throwable) {
            DiagnosticsLogger.record(
                this,
                "Virtual launch failed: ${error.javaClass.name}: ${error.message}"
            )
            Toast.makeText(
                this,
                "Virtual launch failed: ${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun removeInstance(app: InstalledApp, instanceLabel: String) {
        val key = instancePreferenceKey(app.packageName, instanceLabel)
        if (pendingInstanceKeys.contains(key)) {
            Toast.makeText(this, "Wait for setup to finish first.", Toast.LENGTH_SHORT).show()
            return
        }

        val userId = preferences.getInt(userIdKey(key), -1)
        Thread {
            try {
                if (userId > 0) BlackBoxRuntime.deleteUser(userId)
                removeInstancePreferences(app.packageName, instanceLabel)
                DiagnosticsLogger.record(
                    this,
                    "Removed virtual instance $instanceLabel for ${app.packageName}; userId=$userId"
                )
                runOnUiThread {
                    instanceErrors.remove(key)
                    renderInstances()
                    Toast.makeText(this, "$instanceLabel removed.", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Could not remove instance: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun removeLibraryApp(app: InstalledApp) {
        val labels = getInstanceLabels(app.packageName)
        val userIds = labels.mapNotNull { label ->
            preferences.getInt(userIdKey(instancePreferenceKey(app.packageName, label)), -1)
                .takeIf { it > 0 }
        }

        Thread {
            try {
                userIds.forEach { BlackBoxRuntime.deleteUser(it) }
                labels.forEach { label -> removeInstancePreferences(app.packageName, label) }
                val packages = getLibraryPackages().toMutableSet()
                packages.remove(app.packageName)
                saveLibraryPackages(packages)
                saveInstanceLabels(app.packageName, emptySet())
                DiagnosticsLogger.record(this, "Removed ${app.packageName} and ${userIds.size} virtual user(s)")
                runOnUiThread {
                    renderLibrary()
                    renderInstances()
                    renderInstalledApps()
                    Toast.makeText(this, "${app.label} removed from CloneHub.", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Could not remove app instances: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun instanceRow(
        app: InstalledApp,
        instanceLabel: String,
        status: String,
        actionLabel: String,
        actionEnabled: Boolean,
        onAction: () -> Unit,
        onRemove: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            setBackgroundColor(0xFFFFFFFF.toInt())

            val iconView = ImageView(this@MainActivity).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(iconView, LinearLayout.LayoutParams(dp(42), dp(42)))

            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
                addView(TextView(this@MainActivity).apply {
                    text = "${app.label} • $instanceLabel"
                    textSize = 14f
                    setTextColor(0xFF202027.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = status
                    textSize = 11f
                    setTextColor(0xFF777783.toInt())
                })
            }
            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = actionLabel
                isAllCaps = false
                isEnabled = actionEnabled
                setOnClickListener { onAction() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Remove"
                isAllCaps = false
                setOnClickListener { onRemove() }
            })
        }
    }

    private fun getInstalledApp(appPackage: String): InstalledApp? {
        return try {
            val info = packageManager.getApplicationInfo(appPackage, 0)
            InstalledApp(
                packageName = appPackage,
                label = packageManager.getApplicationLabel(info).toString(),
                icon = packageManager.getApplicationIcon(info)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getInstanceLabels(appPackage: String): Set<String> =
        preferences.getStringSet(instanceListKey(appPackage), emptySet())?.toSet() ?: emptySet()

    private fun saveInstanceLabels(appPackage: String, labels: Set<String>) {
        preferences.edit().putStringSet(instanceListKey(appPackage), labels).apply()
    }

    private fun instanceListKey(appPackage: String) = "instances_$appPackage"

    private fun instancePreferenceKey(appPackage: String, instanceLabel: String) =
        "${appPackage}_${instanceLabel.replace(' ', '_')}"

    private fun userIdKey(instanceKey: String) = "virtual_user_$instanceKey"

    private fun readyKey(instanceKey: String) = "virtual_ready_$instanceKey"

    private fun removeInstancePreferences(appPackage: String, instanceLabel: String) {
        val key = instancePreferenceKey(appPackage, instanceLabel)
        preferences.edit()
            .remove(userIdKey(key))
            .remove(readyKey(key))
            .apply()
        val labels = getInstanceLabels(appPackage).toMutableSet()
        labels.remove(instanceLabel)
        saveInstanceLabels(appPackage, labels)
    }

    private fun appRow(
        app: InstalledApp,
        actionLabel: String,
        onAction: () -> Unit,
        secondaryActionLabel: String? = null,
        onSecondaryAction: (() -> Unit)? = null
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            setBackgroundColor(0xFFFFFFFF.toInt())

            val iconView = ImageView(this@MainActivity).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(iconView, LinearLayout.LayoutParams(dp(42), dp(42)))

            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
                addView(TextView(this@MainActivity).apply {
                    text = app.label
                    textSize = 15f
                    setTextColor(0xFF202027.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = app.packageName
                    textSize = 10f
                    setTextColor(0xFF777783.toInt())
                })
            }
            addView(
                details,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            if (secondaryActionLabel != null && onSecondaryAction != null) {
                addView(Button(this@MainActivity).apply {
                    text = secondaryActionLabel
                    isAllCaps = false
                    setOnClickListener { onSecondaryAction() }
                })
            }

            addView(Button(this@MainActivity).apply {
                text = actionLabel
                isAllCaps = false
                isEnabled = actionLabel != "Added"
                setOnClickListener { onAction() }
            })
        }
    }

    private fun emptyMessage(message: String) = TextView(this).apply {
        text = message
        textSize = 13f
        setTextColor(0xFF777783.toInt())
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(0xFFFFFFFF.toInt())
    }

    private fun rowLayoutParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(8) }

    private fun getLibraryPackages(): Set<String> =
        preferences.getStringSet(KEY_LIBRARY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    private fun saveLibraryPackages(packages: Set<String>) {
        preferences.edit().putStringSet(KEY_LIBRARY_PACKAGES, packages).apply()
    }

    private fun getAppLabel(appPackage: String): String {
        return try {
            val info = packageManager.getApplicationInfo(appPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            appPackage
        }
    }

    private fun exportDiagnostics() {
        try {
            DiagnosticsLogger.record(this, "User requested diagnostics export")
            val uri = DiagnosticsLogger.exportToDownloads(this)
            if (uri != null) {
                Toast.makeText(
                    this,
                    "Saved to Downloads/CloneHub/clonehub-diagnostics.txt",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "Could not save diagnostics file.", Toast.LENGTH_LONG).show()
            }
        } catch (error: Exception) {
            DiagnosticsLogger.record(
                this,
                "Diagnostics export failed: ${error.javaClass.name}: ${error.message}"
            )
            Log.e("CloneHubDiagnostics", "Unable to export diagnostics", error)
            Toast.makeText(
                this,
                "Diagnostics export failed: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        DiagnosticsLogger.record(this, "Activity onResume")
        updateEngineStatus()
        if (::libraryContainer.isInitialized && ::instancesContainer.isInitialized && ::installedContainer.isInitialized) {
            renderLibrary()
            renderInstances()
            renderInstalledApps()
        }
    }

    override fun onPause() {
        DiagnosticsLogger.record(this, "Activity onPause")
        super.onPause()
    }

    override fun onStop() {
        DiagnosticsLogger.record(this, "Activity onStop")
        super.onStop()
    }

    override fun onDestroy() {
        DiagnosticsLogger.record(
            this,
            "Activity onDestroy; changingConfigurations=$isChangingConfigurations"
        )
        super.onDestroy()
    }

    private data class InstalledApp(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )

    companion object {
        private const val KEY_LIBRARY_PACKAGES = "library_packages"
    }
}
