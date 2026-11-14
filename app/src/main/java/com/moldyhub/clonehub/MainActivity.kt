package com.moldyhub.clonehub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * CloneHub V1 installed-app library.
 *
 * The library lists launchable, user-installed apps on this device. Adding an
 * app records its package name only; it does not clone or execute the app in
 * an isolated runtime yet.
 */
class MainActivity : Activity() {

    private lateinit var libraryContainer: LinearLayout
    private lateinit var instancesContainer: LinearLayout
    private lateinit var installedContainer: LinearLayout
    private lateinit var workerStatusText: TextView
    private lateinit var workerTestButton: Button

    private val preferences by lazy {
        getSharedPreferences("clonehub_library", MODE_PRIVATE)
    }

    private val instanceStore by lazy {
        VirtualAppInstanceStore(this)
    }

    private val lifecycleController by lazy {
        VirtualAppLifecycleController(instanceStore, UnavailableVirtualAppRuntime())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val subtitle = TextView(this).apply {
            text = "Choose installed apps for your library"
            textSize = 14f
            setTextColor(0xFF666672.toInt())
            setPadding(0, dp(4), 0, dp(12))
        }

        workerStatusText = TextView(this).apply {
            text = "Not tested yet"
            textSize = 12f
            setTextColor(0xFF666672.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }

        workerTestButton = Button(this).apply {
            text = "Test worker"
            isAllCaps = false
            setOnClickListener { testCveWorker() }
        }

        val workerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(TextView(this@MainActivity).apply {
                text = "CVE worker • isolated process"
                textSize = 15f
                setTextColor(0xFF202027.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(workerStatusText)
            addView(workerTestButton)
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
        root.addView(subtitle)
        root.addView(workerPanel)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(8) }
        )

        setContentView(root)
        renderLibrary()
        renderInstances()
        renderInstalledApps()
    }

    private fun testCveWorker() {
        workerTestButton.isEnabled = false
        workerTestButton.text = "Testing..."
        workerStatusText.text = "Starting the isolated CVE worker and checking IPC..."

        CveWorkerClient(this).ping { result ->
            if (isFinishing || isDestroyed) return@ping
            workerTestButton.isEnabled = true
            workerTestButton.text = "Test worker"
            when (result) {
                is CveWorkerClient.Result.Ready -> {
                    val handshake = result.handshake
                    workerStatusText.text =
                        "ONLINE • PID ${handshake.processId} • UID ${handshake.workerUid} • separate from host UID ${android.os.Process.myUid()}"
                    Toast.makeText(this, "CVE isolated worker verified.", Toast.LENGTH_SHORT).show()
                }
                is CveWorkerClient.Result.Failed -> {
                    workerStatusText.text = "FAILED • ${result.reason}"
                    Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                }
            }
        }
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
            val app = try {
                val info = packageManager.getApplicationInfo(appPackage, 0)
                InstalledApp(
                    packageName = appPackage,
                    label = packageManager.getApplicationLabel(info).toString(),
                    icon = packageManager.getApplicationIcon(info)
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

            if (app == null) {
                val updated = getLibraryPackages().toMutableSet()
                updated.remove(appPackage)
                saveLibraryPackages(updated)
            } else {
                val row = appRow(
                    app = app,
                    actionLabel = "Remove",
                    onAction = {
                        val updated = getLibraryPackages().toMutableSet()
                        updated.remove(appPackage)
                        saveLibraryPackages(updated)
                        instanceStore.removeAllForPackage(appPackage)
                        renderLibrary()
                        renderInstances()
                        renderInstalledApps()
                    },
                    secondaryActionLabel = "Add instance",
                    onSecondaryAction = { addInstance(app) },
                    tertiaryActionLabel = "Prepare APK",
                    onTertiaryAction = { prepareLibraryApp(appPackage) }
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
            instanceStore.getForPackage(appPackage).forEach { instance ->
                instanceCount++
                instancesContainer.addView(
                    instanceRow(
                        app = app,
                        instance = instance,
                        onRemove = {
                            instanceStore.remove(instance.instanceId)
                            renderInstances()
                        },
                        onStart = {
                            handleLifecycleResult(lifecycleController.start(instance.instanceId))
                        },
                        onStop = {
                            handleLifecycleResult(lifecycleController.stop(instance.instanceId))
                        }
                    ),
                    rowLayoutParams()
                )
            }
        }
        if (instanceCount == 0) {
            instancesContainer.addView(emptyMessage("No instances created yet. Add an instance from an app in My library."))
        }
    }

    private fun prepareLibraryApp(appPackage: String) {
        workerStatusText.text = "Preparing installed APK files for $appPackage..."
        Thread {
            val result = ApkSetPreparer(this).prepare(appPackage)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is ApkPreparationResult.Success -> {
                        val set = result.apkSet
                        val sizeMb = set.totalBytes / (1024.0 * 1024.0)
                        workerStatusText.text =
                            "APK SET READY • ${set.apkFiles.size} file(s) • %.1f MB • version ${set.versionCode}".format(sizeMb)
                        val verb = if (result.alreadyPrepared) "already prepared" else "prepared"
                        Toast.makeText(
                            this,
                            "${getAppLabel(appPackage)} APK set $verb.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is ApkPreparationResult.Failure -> {
                        workerStatusText.text = "APK PREPARATION FAILED • ${result.reason}"
                        Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun addInstance(app: InstalledApp) {
        val existing = instanceStore.getForPackage(app.packageName).size
        val instanceName = "Instance ${existing + 1}"
        val instance = instanceStore.create(app.packageName, instanceName)
        renderInstances()
        Toast.makeText(this, "${instance.configuration.displayName} created and saved.", Toast.LENGTH_SHORT).show()
    }

    private fun instanceRow(
        app: InstalledApp,
        instance: com.moldyhub.clonehub.cve.VirtualAppInstance,
        onRemove: () -> Unit,
        onStart: () -> Unit,
        onStop: () -> Unit
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
                    text = "${app.label} • ${instance.configuration.displayName ?: "Instance"}"
                    textSize = 14f
                    setTextColor(0xFF202027.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = if (instance.state == com.moldyhub.clonehub.cve.InstanceState.FAILED) {
                        "FAILED • runtime unavailable • ID ${instance.instanceId.take(8)}"
                    } else {
                        "${instance.state.name} • ID ${instance.instanceId.take(8)}"
                    }
                    textSize = 11f
                    setTextColor(0xFF777783.toInt())
                })
            }
            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val lifecycleButton = Button(this@MainActivity).apply {
                text = when (instance.state) {
                    com.moldyhub.clonehub.cve.InstanceState.RUNNING -> "Stop"
                    com.moldyhub.clonehub.cve.InstanceState.STARTING,
                    com.moldyhub.clonehub.cve.InstanceState.STOPPING -> "Wait"
                    else -> "Start"
                }
                isAllCaps = false
                isEnabled = instance.state != com.moldyhub.clonehub.cve.InstanceState.STARTING &&
                    instance.state != com.moldyhub.clonehub.cve.InstanceState.STOPPING
                setOnClickListener {
                    if (instance.state == com.moldyhub.clonehub.cve.InstanceState.RUNNING) onStop() else onStart()
                }
            }
            addView(lifecycleButton)
            addView(Button(this@MainActivity).apply {
                text = "Remove"
                isAllCaps = false
                setOnClickListener { onRemove() }
            })
        }
    }

    private fun handleLifecycleResult(result: InstanceLifecycleResult) {
        when (result) {
            is InstanceLifecycleResult.Running -> {
                Toast.makeText(this, "Runtime started.", Toast.LENGTH_SHORT).show()
            }
            is InstanceLifecycleResult.Stopped -> {
                Toast.makeText(this, "Runtime stopped.", Toast.LENGTH_SHORT).show()
            }
            is InstanceLifecycleResult.Failed -> {
                Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
            }
        }
        renderInstances()
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

    private fun appRow(
        app: InstalledApp,
        actionLabel: String,
        onAction: () -> Unit,
        secondaryActionLabel: String? = null,
        onSecondaryAction: (() -> Unit)? = null,
        tertiaryActionLabel: String? = null,
        onTertiaryAction: (() -> Unit)? = null
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
                val name = TextView(this@MainActivity).apply {
                    text = app.label
                    textSize = 15f
                    setTextColor(0xFF202027.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val packageNameView = TextView(this@MainActivity).apply {
                    text = app.packageName
                    textSize = 10f
                    setTextColor(0xFF777783.toInt())
                }
                addView(name)
                addView(packageNameView)
            }
            addView(
                details,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            val action = Button(this@MainActivity).apply {
                text = actionLabel
                isAllCaps = false
                isEnabled = actionLabel != "Added"
                setOnClickListener { onAction() }
            }
            val actionColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                if (tertiaryActionLabel != null && onTertiaryAction != null) {
                    addView(Button(this@MainActivity).apply {
                        text = tertiaryActionLabel
                        isAllCaps = false
                        setOnClickListener { onTertiaryAction() }
                    })
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    addView(Button(this@MainActivity).apply {
                        text = secondaryActionLabel
                        isAllCaps = false
                        setOnClickListener { onSecondaryAction() }
                    })
                }
                addView(action)
            }
            addView(actionColumn)
        }
    }

    private fun launchInstalledApp(app: InstalledApp) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launchIntent == null) {
                Toast.makeText(this, "No launch activity found for ${app.label}.", Toast.LENGTH_SHORT).show()
                return
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } catch (error: Exception) {
            Toast.makeText(this, "Could not open ${app.label}.", Toast.LENGTH_LONG).show()
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::libraryContainer.isInitialized && ::instancesContainer.isInitialized && ::installedContainer.isInitialized) {
            renderLibrary()
            renderInstances()
            renderInstalledApps()
        }
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
