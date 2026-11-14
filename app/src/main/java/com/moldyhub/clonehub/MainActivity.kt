package com.moldyhub.clonehub

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * CloneHub multi-instance dashboard.
 *
 * This screen manages app-library entries and prepared instance profiles.
 * The execution backend is intentionally reported as pending until a real
 * isolated target-app process can be started and verified.
 */
class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var instancesContainer: LinearLayout
    private lateinit var libraryContainer: LinearLayout
    private lateinit var installedContainer: LinearLayout
    private lateinit var workerStatusText: TextView
    private lateinit var instanceCountText: TextView
    private lateinit var libraryCountText: TextView
    private lateinit var workerTestButton: Button

    private val preferences by lazy {
        getSharedPreferences("clonehub_library", MODE_PRIVATE)
    }

    private val instanceStore by lazy { VirtualAppInstanceStore(this) }
    private val runtimePreferences by lazy {
        getSharedPreferences("clonehub_runtime_instances", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildDashboard()
        refreshDashboard()
    }

    private fun buildDashboard() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 15, 29))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(buildHeader())
        content.addView(buildOverviewCard(), margins(top = 20))
        content.addView(buildSectionHeader("YOUR INSTANCES", "Manage your app spaces"), margins(top = 24, bottom = 10))

        instanceCountText = TextView(this).apply {
            text = "0 profiles"
            textSize = 12f
            setTextColor(Color.rgb(178, 168, 255))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        val instanceHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Instances"
                textSize = 19f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(instanceCountText)
        }
        content.addView(instanceHeading, margins(bottom = 10))

        instancesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(instancesContainer)

        content.addView(buildSectionHeader("APP LIBRARY", "Apps ready to create instances"), margins(top = 24, bottom = 10))
        libraryCountText = TextView(this).apply {
            text = "0 apps"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.END
        }
        val libraryHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "My apps"
                textSize = 19f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(libraryCountText)
        }
        content.addView(libraryHeading, margins(bottom = 10))
        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(libraryContainer)

        content.addView(buildSectionHeader("ADD APPS", "Choose an installed app"), margins(top = 24, bottom = 10))
        installedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(installedContainer)

        content.addView(buildDiagnosticsCard(), margins(top = 24, bottom = 8))

        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(this).apply {
            text = "C"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(112, 91, 255), 16)
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "CloneHub"
                textSize = 23f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "MULTI-INSTANCE MANAGER"
                textSize = 9f
                letterSpacing = 0.12f
                setTextColor(MUTED)
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "ALPHA 24"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(196, 188, 255))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(Color.rgb(37, 32, 70), 20)
        })
        return header
    }

    private fun buildOverviewCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(27, 30, 55), 22)
        }
        card.addView(TextView(this).apply {
            text = "YOUR APPS. YOUR SPACES."
            textSize = 10f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(174, 162, 255))
        })
        card.addView(TextView(this).apply {
            text = "One app, multiple instances."
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(5))
        })
        card.addView(TextView(this).apply {
            text = "Build separate app spaces and accounts. CloneHub is preparing the runtime foundation."
            textSize = 13f
            setTextColor(Color.rgb(190, 193, 211))
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        stats.addView(statTile("3", "STARTER SLOTS / APP"), LinearLayout.LayoutParams(0, dp(66), 1f))
        stats.addView(View(this).apply { setBackgroundColor(Color.rgb(62, 65, 91)) },
            LinearLayout.LayoutParams(dp(1), dp(42)).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(12)
                rightMargin = dp(12)
            })
        stats.addView(statTile("PENDING", "RUNTIME STATUS"), LinearLayout.LayoutParams(0, dp(66), 1f))
        card.addView(stats)
        return card
    }

    private fun statTile(value: String, label: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = if (value.length > 4) 14f else 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 9f
                letterSpacing = 0.06f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun buildSectionHeader(eyebrow: String, description: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = eyebrow
                textSize = 10f
                letterSpacing = 0.13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(156, 144, 255))
            })
            addView(TextView(this@MainActivity).apply {
                text = description
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun buildDiagnosticsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = rounded(Color.rgb(23, 27, 44), 18)
        }
        card.addView(TextView(this).apply {
            text = "RUNTIME DIAGNOSTICS"
            textSize = 10f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(174, 162, 255))
        })
        card.addView(TextView(this).apply {
            text = "Isolated worker"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(3))
        })
        workerStatusText = TextView(this).apply {
            text = "NewBlackbox is linked but stays dormant until you install an instance. Tap Install to initialize the engine on demand."
            textSize = 12f
            setTextColor(MUTED)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        card.addView(workerStatusText)
        workerTestButton = Button(this).apply {
            text = "Test isolated worker"
            isAllCaps = false
            setOnClickListener { testCveWorker() }
        }
        card.addView(workerTestButton, margins(top = 10))
        return card
    }

    private fun refreshDashboard() {
        renderInstances()
        renderLibrary()
        renderInstalledApps()
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
                        appPackage,
                        packageManager.getApplicationLabel(appInfo).toString(),
                        packageManager.getApplicationIcon(appInfo)
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun renderInstalledApps() {
        installedContainer.removeAllViews()
        val added = getLibraryPackages()
        val apps = getInstalledUserApps().filter { !added.contains(it.packageName) }
        if (apps.isEmpty()) {
            installedContainer.addView(emptyCard("All detected apps are already in your library."))
            return
        }
        apps.forEach { app ->
            installedContainer.addView(appCard(app, "Add to library", {
                val packages = getLibraryPackages().toMutableSet()
                packages.add(app.packageName)
                saveLibraryPackages(packages)
                refreshDashboard()
                Toast.makeText(this, "${app.label} added to your library.", Toast.LENGTH_SHORT).show()
            }, showPrepare = false), cardMargins())
        }
    }

    private fun renderLibrary() {
        libraryContainer.removeAllViews()
        val packages = getLibraryPackages().toList().sortedBy { getAppLabel(it).lowercase() }
        libraryCountText.text = "${packages.size} apps"
        if (packages.isEmpty()) {
            libraryContainer.addView(emptyCard("Your library is empty. Add an installed app below to get started."))
            return
        }
        packages.forEach { appPackage ->
            val app = getInstalledApp(appPackage)
            if (app == null) {
                val updated = getLibraryPackages().toMutableSet()
                updated.remove(appPackage)
                saveLibraryPackages(updated)
            } else {
                val count = instanceStore.getForPackage(appPackage).size
                libraryContainer.addView(
                    appCard(
                        app,
                        "Add instance  •  ${count}/3",
                        { addInstance(app) },
                        showPrepare = true,
                        onPrepare = { prepareLibraryApp(appPackage) }
                    ),
                    cardMargins()
                )
            }
        }
    }

    private fun renderInstances() {
        instancesContainer.removeAllViews()
        var total = 0
        getLibraryPackages().toList().sortedBy { getAppLabel(it).lowercase() }.forEach { appPackage ->
            val app = getInstalledApp(appPackage) ?: return@forEach
            instanceStore.getForPackage(appPackage).forEach { instance ->
                total++
                instancesContainer.addView(instanceCard(app, instance), cardMargins())
            }
        }
        instanceCountText.text = "${total} profiles"
        if (total == 0) {
            instancesContainer.addView(emptyCard("No instances yet. Add an app to your library, then create its first instance."))
        }
    }

    private fun appCard(
        app: InstalledApp,
        actionLabel: String,
        onAction: () -> Unit,
        showPrepare: Boolean,
        onPrepare: (() -> Unit)? = null
    ): View {
        val card = horizontalCard()
        card.addView(appIcon(app), LinearLayout.LayoutParams(dp(48), dp(48)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(TextView(this@MainActivity).apply {
                text = app.label
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = app.packageName
                textSize = 10f
                setTextColor(MUTED)
                maxLines = 1
            })
        }
        card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        if (showPrepare && onPrepare != null) {
            actions.addView(smallButton("Prepare APK", false, onPrepare))
        }
        actions.addView(smallButton(actionLabel, true, onAction))
        card.addView(actions)
        return card
    }

    private fun instanceCard(
        app: InstalledApp,
        instance: com.moldyhub.clonehub.cve.VirtualAppInstance
    ): View {
        val card = horizontalCard()
        card.addView(appIcon(app), LinearLayout.LayoutParams(dp(44), dp(44)))
        val userId = runtimePreferences.getInt(runtimeUserKey(instance.instanceId), -1)
        val runtimeStatus = when {
            !NewBlackboxRuntime.initialized -> "NewBlackbox not initialized"
            userId > 0 -> "Installed in virtual user #$userId"
            else -> "Prepared profile • not installed in runtime"
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(5), 0)
            addView(TextView(this@MainActivity).apply {
                text = "${app.label}  •  ${instance.configuration.displayName ?: "Instance"}"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = "PROFILE  •  ID ${instance.instanceId.take(8)}"
                textSize = 9f
                letterSpacing = 0.03f
                setTextColor(Color.rgb(175, 164, 255))
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = runtimeStatus
                textSize = 10f
                setTextColor(MUTED)
                setPadding(0, dp(3), 0, 0)
            })
        }
        card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val actionLabel = if (userId > 0) "Launch" else "Install"
        actions.addView(smallButton(actionLabel, true) {
            if (userId > 0) launchRuntimeInstance(instance)
            else installRuntimeInstance(instance)
        })
        actions.addView(smallButton("Remove", false) {
            removeRuntimeInstance(instance)
        }, margins(top = 4))
        card.addView(actions)
        return card
    }

    private fun runtimeUserKey(instanceId: String) = "user_$instanceId"

    private fun installRuntimeInstance(instance: com.moldyhub.clonehub.cve.VirtualAppInstance) {
        if (!NewBlackboxRuntime.initialized) {
            workerStatusText.text = "INITIALIZING NEWBLACKBOX • first-use setup..."
            try {
                NewBlackboxRuntime.initialize(applicationContext)
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                workerStatusText.text = "NEWBLACKBOX INITIALIZATION FAILED • $message"
                DiagnosticsLogger.record(this, "NewBlackbox initialization failed: ${error.javaClass.name}: $message")
                Toast.makeText(this, "NewBlackbox initialization failed: $message", Toast.LENGTH_LONG).show()
                return
            }
        }
        val appInfo = try {
            packageManager.getApplicationInfo(instance.packageName, 0)
        } catch (_: Exception) {
            Toast.makeText(this, "The selected app is no longer installed.", Toast.LENGTH_LONG).show()
            return
        }
        if (!appInfo.splitSourceDirs.isNullOrEmpty()) {
            Toast.makeText(
                this,
                "This runtime test currently supports single-APK apps only. Split APK installation is not connected yet.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        workerStatusText.text = "Installing ${instance.packageName} into a NewBlackbox virtual user..."
        Thread {
            var userId = -1
            try {
                userId = NewBlackboxRuntime.allocateUserId(this)
                NewBlackboxRuntime.installApp(instance.packageName, userId)
                check(runtimePreferences.edit()
                    .putInt(runtimeUserKey(instance.instanceId), userId)
                    .commit()) { "Could not save the virtual user mapping." }
                DiagnosticsLogger.record(
                    this,
                    "NewBlackbox virtual install succeeded: package=${instance.packageName}, instance=${instance.instanceId}, userId=$userId"
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    workerStatusText.text = "VIRTUAL INSTALL COMPLETE • ${instance.packageName} • user $userId. Launch is the next smoke test."
                    refreshDashboard()
                    Toast.makeText(this, "Installed into virtual user #$userId.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                if (userId > 0) runCatching { NewBlackboxRuntime.deleteUser(userId) }
                val message = error.message ?: error.javaClass.simpleName
                DiagnosticsLogger.record(this, "NewBlackbox virtual install failed: ${error.javaClass.name}: $message")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    workerStatusText.text = "VIRTUAL INSTALL FAILED • $message"
                    Toast.makeText(this, "Virtual install failed: $message", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchRuntimeInstance(instance: com.moldyhub.clonehub.cve.VirtualAppInstance) {
        if (!NewBlackboxRuntime.initialized) {
            try {
                workerStatusText.text = "INITIALIZING NEWBLACKBOX..."
                NewBlackboxRuntime.initialize(applicationContext)
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                workerStatusText.text = "NEWBLACKBOX INITIALIZATION FAILED • $message"
                DiagnosticsLogger.record(this, "NewBlackbox initialization failed: ${error.javaClass.name}: $message")
                Toast.makeText(this, "NewBlackbox initialization failed: $message", Toast.LENGTH_LONG).show()
                return
            }
        }
        val userId = runtimePreferences.getInt(runtimeUserKey(instance.instanceId), -1)
        if (userId <= 0) {
            installRuntimeInstance(instance)
            return
        }
        try {
            val launched = NewBlackboxRuntime.launchApp(instance.packageName, userId)
            if (launched) {
                workerStatusText.text = "VIRTUAL LAUNCH REQUEST SENT • ${instance.packageName} • user $userId"
                DiagnosticsLogger.record(this, "NewBlackbox launch accepted: package=${instance.packageName}, userId=$userId")
            } else {
                Toast.makeText(this, "NewBlackbox could not start this app instance.", Toast.LENGTH_LONG).show()
            }
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            DiagnosticsLogger.record(this, "NewBlackbox virtual launch failed: ${error.javaClass.name}: $message")
            Toast.makeText(this, "Virtual launch failed: $message", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeRuntimeInstance(instance: com.moldyhub.clonehub.cve.VirtualAppInstance) {
        val userId = runtimePreferences.getInt(runtimeUserKey(instance.instanceId), -1)
        if (userId > 0 && !NewBlackboxRuntime.initialized) {
            try {
                NewBlackboxRuntime.initialize(applicationContext)
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                Toast.makeText(this, "NewBlackbox initialization failed: $message", Toast.LENGTH_LONG).show()
                return
            }
        }
        Thread {
            try {
                if (userId > 0) NewBlackboxRuntime.deleteUser(userId)
                runtimePreferences.edit().remove(runtimeUserKey(instance.instanceId)).commit()
                instanceStore.remove(instance.instanceId)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshDashboard()
                    Toast.makeText(this, "Instance removed.", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "Could not remove instance: $message", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun horizontalCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(10), dp(12))
            background = rounded(Color.rgb(24, 29, 47), 17)
        }
    }

    private fun appIcon(app: InstalledApp): ImageView {
        return ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun smallButton(label: String, primary: Boolean, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            minHeight = dp(36)
            minimumHeight = dp(36)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            if (primary) {
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(103, 83, 242), 12)
            } else {
                setTextColor(Color.rgb(211, 207, 236))
                background = rounded(Color.rgb(48, 48, 72), 12)
            }
            setOnClickListener { action() }
        }
    }

    private fun emptyCard(message: String): View {
        return TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(15), dp(16), dp(15), dp(16))
            background = rounded(Color.rgb(21, 26, 42), 15)
        }
    }

    private fun prepareLibraryApp(appPackage: String) {
        workerStatusText.text = "Preparing APK files for $appPackage..."
        Thread {
            val result = ApkSetPreparer(this).prepare(appPackage)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is ApkPreparationResult.Success -> {
                        val set = result.apkSet
                        val abiSummary = set.inspection.nativeAbis.joinToString().ifBlank { "no native libraries" }
                        val compatibility = if (set.inspection.deviceAbiCompatible) "ABI compatible" else "ABI INCOMPATIBLE"
                        workerStatusText.text = "APK VALIDATED • ${set.apkFiles.size} file(s) • version ${set.versionCode} • SHA-256 recorded • ${compatibility} • native ABI: ${abiSummary}. This is preflight only; guest execution is not implemented yet."
                        Toast.makeText(this, "APK set prepared.", Toast.LENGTH_LONG).show()
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
        if (existing >= MAX_INSTANCES_PER_APP) {
            Toast.makeText(this, "The first build supports up to 3 prepared profiles per app.", Toast.LENGTH_LONG).show()
            return
        }
        workerStatusText.text = "Preparing APK set before creating an instance..."
        Thread {
            val preparation = ApkSetPreparer(this).prepare(app.packageName)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (preparation) {
                    is ApkPreparationResult.Success -> {
                        val next = instanceStore.getForPackage(app.packageName).size + 1
                        val instance = instanceStore.create(
                            app.packageName,
                            "Instance $next",
                            preparation.apkSet.versionCode
                        )
                        val prepared = preparation.apkSet
                        val abiSummary = prepared.inspection.nativeAbis.joinToString().ifBlank { "no native libraries" }
                        val compatibility = if (prepared.inspection.deviceAbiCompatible) "ABI compatible" else "ABI INCOMPATIBLE"
                        workerStatusText.text = "PROFILE + APK PREFLIGHT READY • ${prepared.apkFiles.size} APK file(s) • ${compatibility} • native ABI: ${abiSummary}. Runtime execution is still pending."
                        refreshDashboard()
                        Toast.makeText(this, "${instance.configuration.displayName} profile created.", Toast.LENGTH_LONG).show()
                    }
                    is ApkPreparationResult.Failure -> {
                        workerStatusText.text = "INSTANCE CREATION FAILED • ${preparation.reason}"
                        Toast.makeText(this, preparation.reason, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun testCveWorker() {
        workerTestButton.isEnabled = false
        workerTestButton.text = "Testing..."
        workerStatusText.text = "Checking the isolated helper process..."
        CveWorkerClient(this).ping { result ->
            if (isFinishing || isDestroyed) return@ping
            workerTestButton.isEnabled = true
            workerTestButton.text = "Test isolated worker"
            when (result) {
                is CveWorkerClient.Result.Ready -> {
                    val h = result.handshake
                    workerStatusText.text = "WORKER ONLINE • PID ${h.processId} • UID ${h.workerUid} • API ${h.deviceSdkInt} • ABI ${h.supportedAbis.joinToString()} • Helper only; target-app execution is not implemented."
                    Toast.makeText(this, "Isolated helper process verified.", Toast.LENGTH_SHORT).show()
                }
                is CveWorkerClient.Result.Failed -> {
                    workerStatusText.text = "WORKER FAILED • ${result.reason}"
                    Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getInstalledApp(appPackage: String): InstalledApp? {
        return try {
            val info = packageManager.getApplicationInfo(appPackage, 0)
            InstalledApp(
                appPackage,
                packageManager.getApplicationLabel(info).toString(),
                packageManager.getApplicationIcon(info)
            )
        } catch (_: Exception) {
            null
        }
    }

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

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun margins(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun cardMargins() = margins(bottom = 9)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) refreshDashboard()
    }

    private data class InstalledApp(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )

    companion object {
        private const val KEY_LIBRARY_PACKAGES = "library_packages"
        private const val MAX_INSTANCES_PER_APP = 3
        private val MUTED = Color.rgb(148, 154, 177)
    }
}
