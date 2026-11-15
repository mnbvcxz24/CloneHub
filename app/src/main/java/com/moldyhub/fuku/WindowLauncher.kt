package com.moldyhub.fuku

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager

/** A clone that Fuku built and Android has installed as its own package. */
data class InstalledClone(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

/**
 * Opens installed clones in separate, resizable windows using Android's own windowing.
 *
 * Fuku does not host the clones; each one is a normal installed app. We only ask the system to
 * start it with launch bounds. The system draws the title bar and the minimize/maximize/close
 * buttons, and it only floats the window when freeform (desktop) windowing is available.
 * Otherwise Android ignores the bounds and opens the clone full screen.
 */
object WindowLauncher {
    // Matches the package names produced by CloneApkBuilder, e.g. com.roblox.client.fuku.c001
    private val CLONE_PACKAGE = Regex("""\.fuku\.c\d{3}$""")

    private const val WINDOW_STEP_DP = 40
    private const val WINDOW_CASCADE_SLOTS = 6

    @Suppress("DEPRECATION")
    fun findClones(pm: PackageManager): List<InstalledClone> =
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { CLONE_PACKAGE.containsMatchIn(it.packageName) }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                InstalledClone(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    icon = pm.getApplicationIcon(info)
                )
            }
            .sortedBy { it.packageName }
            .toList()

    /**
     * Starts [packageName] in a floating window. [slot] offsets the window so several windows
     * opened together do not sit exactly on top of each other.
     * Returns null on success, or a short error message.
     */
    fun launchInWindow(context: Context, packageName: String, slot: Int): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "$packageName has no launchable screen."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            val options = ActivityOptions.makeBasic().setLaunchBounds(windowBounds(context, slot))
            context.startActivity(intent, options.toBundle())
            null
        } catch (e: Exception) {
            "Could not open $packageName: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Opens every clone in its own cascaded window. Returns the first error, if any. */
    fun launchAllInWindows(context: Context, clones: List<InstalledClone>): String? {
        var firstError: String? = null
        clones.forEachIndexed { index, clone ->
            val error = launchInWindow(context, clone.packageName, index)
            if (error != null && firstError == null) firstError = error
        }
        return firstError
    }

    /**
     * Best-effort guess at whether this device will honour floating windows. Android does not
     * offer a reliable public check, so this can be wrong in both directions; it is only used to
     * show a hint, never to block launching.
     */
    fun freeformLikelySupported(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return true
        if (pm.hasSystemFeature("android.hardware.type.pc")) return true
        return runCatching {
            Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) != 0
        }.getOrDefault(false)
    }

    internal fun displayBounds(context: Context): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    /** A 16:9 landscape window, cascaded down and right by [slot], kept inside the display. */
    internal fun windowBounds(context: Context, slot: Int): Rect {
        val display = displayBounds(context)
        val density = context.resources.displayMetrics.density
        val width = (display.width() * if (display.width() >= display.height()) 0.5f else 0.9f).toInt()
        val height = width * 9 / 16
        val step = (WINDOW_STEP_DP * density).toInt()
        val offset = step * (1 + slot.coerceAtLeast(0) % WINDOW_CASCADE_SLOTS)
        val left = (display.left + offset).coerceAtMost(display.right - width).coerceAtLeast(display.left)
        val top = (display.top + offset).coerceAtMost(display.bottom - height).coerceAtLeast(display.top)
        return Rect(left, top, left + width, top + height)
    }
}