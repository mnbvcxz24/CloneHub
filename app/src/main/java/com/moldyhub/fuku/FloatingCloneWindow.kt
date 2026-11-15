package com.moldyhub.fuku

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import kotlin.math.abs

/**
 * One floating Fuku window: a draggable, resizable overlay that shows a clone running on its own
 * virtual display. Touches are forwarded to that display through the root helper.
 *
 * All methods must be called on the main thread.
 */
class FloatingCloneWindow(
    private val context: Context,
    private val clone: InstalledClone,
    private val slot: Int,
    private val input: RootInputBridge,
    private val worker: ExecutorService,
    private val onClosed: (String) -> Unit
) {
    private companion object {
        const val DISPLAY_WIDTH = 1280
        const val DISPLAY_HEIGHT = 720
        const val DISPLAY_DPI = 320
        const val TITLE_BAR_DP = 36
        const val MIN_CONTENT_WIDTH_DP = 240
        const val KEEP_ON_SCREEN_DP = 80
        val SAFE_PACKAGE = Regex("[A-Za-z0-9._]+")
    }

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val titleBarPx = dp(TITLE_BAR_DP)
    private val screen = WindowLauncher.displayBounds(context)

    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val bubbleParams = WindowManager.LayoutParams(
        dp(48), dp(48),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private lateinit var root: LinearLayout
    private lateinit var bubble: ImageView
    private lateinit var textureView: TextureView

    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var displayId = -1

    private var minimized = false
    private var maximized = false
    private var restoreX = 0
    private var restoreY = 0
    private var restoreContentWidth = 0
    private var closed = false

    val packageName: String get() = clone.packageName

    fun show() {
        require(SAFE_PACKAGE.matches(clone.packageName)) { "Unsafe package name." }

        val start = WindowLauncher.windowBounds(context, slot)
        setContentWidth(start.width())
        params.x = start.left
        params.y = start.top
        root = buildWindow()
        windowManager.addView(root, params)

        bubble = buildBubble()
        bubbleParams.x = screen.left + dp(8)
        bubbleParams.y = screen.top + dp(120)
    }

    fun restore() {
        if (closed || !minimized) return
        minimized = false
        windowManager.removeView(bubble)
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        root.visibility = View.VISIBLE
        windowManager.updateViewLayout(root, params)
    }

    /** [stopClone] force-stops the clone; it is false when the window failed before the clone started. */
    fun close(stopClone: Boolean = true) {
        if (closed) return
        closed = true
        runCatching { windowManager.removeView(root) }
        if (minimized) runCatching { windowManager.removeView(bubble) }
        val pkg = clone.packageName
        if (stopClone) {
            worker.execute {
                RootShell.run("am force-stop $pkg")
                main.post { releaseDisplay() }
            }
        } else {
            releaseDisplay()
        }
        onClosed(pkg)
    }

    /** Called when the whole service shuts down. */
    fun destroyNow() {
        if (closed) return
        closed = true
        runCatching { windowManager.removeView(root) }
        if (minimized) runCatching { windowManager.removeView(bubble) }
        releaseDisplay()
    }

    // ---- views ---------------------------------------------------------------------------

    private fun buildWindow(): LinearLayout {
        val window = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#202020"))
        }

        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(dp(10), 0, 0, 0)
        }
        titleBar.addView(
            ImageView(context).apply { setImageDrawable(clone.icon) },
            LinearLayout.LayoutParams(dp(22), dp(22))
        )
        titleBar.addView(
            TextView(context).apply {
                text = clone.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        )
        titleBar.addView(titleButton("–") { minimize() })
        titleBar.addView(titleButton("□") { toggleMaximize() })
        titleBar.addView(titleButton("×") { close() })
        attachDrag(titleBar)
        window.addView(titleBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, titleBarPx))

        val content = FrameLayout(context)
        textureView = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    startDisplay(st)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
            setOnTouchListener { view, event ->
                if (displayId >= 0 && view.width > 0 && view.height > 0) {
                    input.sendTouch(
                        displayId, event,
                        DISPLAY_WIDTH.toFloat() / view.width,
                        DISPLAY_HEIGHT.toFloat() / view.height
                    )
                }
                true
            }
        }
        content.addView(textureView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66FFFFFF"))
                cornerRadius = dp(6).toFloat()
            }
        }
        attachResize(handle)
        content.addView(handle, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END))
        window.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return window
    }

    private fun titleButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        minimumWidth = dp(44)
        minHeight = titleBarPx
        layoutParams = LinearLayout.LayoutParams(dp(44), titleBarPx)
        setOnClickListener { onClick() }
    }

    private fun buildBubble(): ImageView = ImageView(context).apply {
        setImageDrawable(clone.icon)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E6111111"))
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        bubbleParams.x = (startX + dx).toInt().coerceIn(screen.left, screen.right - dp(48))
                        bubbleParams.y = (startY + dy).toInt().coerceIn(screen.top, screen.bottom - dp(48))
                        windowManager.updateViewLayout(this, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) restore()
            }
            true
        }
    }

    // ---- window behaviour -------------------------------------------------------------------

    private fun attachDrag(handle: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                }
                MotionEvent.ACTION_MOVE -> if (!maximized) {
                    val keep = dp(KEEP_ON_SCREEN_DP)
                    params.x = (startX + (e.rawX - downX)).toInt()
                        .coerceIn(screen.left - params.width + keep, screen.right - keep)
                    params.y = (startY + (e.rawY - downY)).toInt()
                        .coerceIn(screen.top, screen.bottom - titleBarPx)
                    windowManager.updateViewLayout(root, params)
                }
            }
            true
        }
    }

    private fun attachResize(handle: View) {
        var downX = 0f
        var startWidth = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    startWidth = params.width
                }
                MotionEvent.ACTION_MOVE -> if (!maximized) {
                    val maxWidth = minOf(screen.width() - params.x.coerceAtLeast(0), screen.width())
                    setContentWidth(
                        (startWidth + (e.rawX - downX)).toInt()
                            .coerceIn(dp(MIN_CONTENT_WIDTH_DP), maxOf(maxWidth, dp(MIN_CONTENT_WIDTH_DP)))
                    )
                    windowManager.updateViewLayout(root, params)
                }
            }
            true
        }
    }

    /** Keeps the 16:9 shape of the virtual display; the title bar sits on top of that. */
    private fun setContentWidth(width: Int) {
        params.width = width
        params.height = titleBarPx + width * DISPLAY_HEIGHT / DISPLAY_WIDTH
    }

    private fun minimize() {
        if (minimized || closed) return
        minimized = true
        // Hide instead of removing: removing the view would destroy the virtual display.
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        root.visibility = View.INVISIBLE
        windowManager.updateViewLayout(root, params)
        windowManager.addView(bubble, bubbleParams)
    }

    private fun toggleMaximize() {
        if (closed) return
        if (!maximized) {
            restoreX = params.x
            restoreY = params.y
            restoreContentWidth = params.width
            val fitWidth = minOf(
                screen.width(),
                (screen.height() - titleBarPx) * DISPLAY_WIDTH / DISPLAY_HEIGHT
            )
            setContentWidth(fitWidth)
            params.x = screen.left + (screen.width() - fitWidth) / 2
            params.y = screen.top
            maximized = true
        } else {
            setContentWidth(restoreContentWidth)
            params.x = restoreX
            params.y = restoreY
            maximized = false
        }
        windowManager.updateViewLayout(root, params)
    }

    // ---- virtual display + clone ------------------------------------------------------------

    private fun startDisplay(texture: SurfaceTexture) {
        if (virtualDisplay != null || closed) return
        try {
            texture.setDefaultBufferSize(DISPLAY_WIDTH, DISPLAY_HEIGHT)
            val target = Surface(texture)
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = manager.createVirtualDisplay(
                "fuku-${clone.packageName}",
                DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DPI,
                target,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            ) ?: run {
                target.release()
                fail("Android refused to create a virtual display.")
                return
            }
            surface = target
            virtualDisplay = display
            displayId = display.display.displayId
            launchClone()
        } catch (e: Exception) {
            fail("Could not create the window's display: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun launchClone() {
        val pkg = clone.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        val component = launchIntent?.component?.flattenToString()
        if (launchIntent == null || component == null) {
            fail("$pkg has no launchable screen.")
            return
        }

        // Android can silently ignore secondary-display routing on devices that do not
        // advertise this feature. Check the platform capability and permission before
        // invoking the root shell, so we do not accidentally open the game fullscreen.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fail("Fuku's secondary-display diagnostics require Android 10 or newer.")
            return
        }
        val supportsSecondaryDisplays = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
        )
        val id = displayId
        val allowed = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .isActivityStartAllowedOnDisplay(context, id, launchIntent)
        val displayFlags = virtualDisplay?.display?.flags ?: -1
        Log.i(
            "FukuDisplay",
            "package=$pkg displayId=$id displayFlags=$displayFlags " +
                "supportsSecondaryDisplays=$supportsSecondaryDisplays allowed=$allowed"
        )

        if (!supportsSecondaryDisplays) {
            fail("This Android 15 device does not advertise app launches on secondary displays. " +
                "Fuku stopped the launch to avoid opening the clone fullscreen.")
            return
        }
        if (!allowed) {
            fail("Android blocked launching $pkg on Fuku display $id. " +
                "Fuku stopped the launch to avoid opening it fullscreen.")
            return
        }

        worker.execute {
            // -S restarts the clone so it lands on this display instead of an existing task.
            val result = RootShell.run("am start -S --display $id -n '$component'")
            val failed = result.exitCode != 0 ||
                result.output.contains("Error", ignoreCase = true) ||
                result.output.contains("Exception")
            val activityDump = if (!failed) {
                RootShell.run("dumpsys activity activities").output
            } else {
                ""
            }
            val dumpLines = activityDump.lines()
            val matchingIndexes = dumpLines.indices.filter { dumpLines[it].contains(pkg) }
            val taskExcerpt = matchingIndexes.flatMap { index ->
                (maxOf(0, index - 2)..minOf(dumpLines.lastIndex, index + 3)).toList()
            }.distinct().sorted().joinToString("\\n") { dumpLines[it] }.take(1600)
            Log.i(
                "FukuDisplay",
                "launchExitCode=${result.exitCode} launchOutput=${result.output} " +
                    "taskExcerpt=${taskExcerpt.ifBlank { "No matching task record found in dumpsys." }}"
            )
            main.post {
                if (failed) {
                    fail("Could not start the clone: ${result.output.take(200)}")
                } else {
                    Toast.makeText(
                        context,
                        "Android accepted the launch request for display $id. " +
                            "This does not yet confirm where the game rendered; please check the window.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun fail(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        close(stopClone = false)
    }

    private fun releaseDisplay() {
        runCatching { virtualDisplay?.release() }
        runCatching { surface?.release() }
        virtualDisplay = null
        surface = null
        displayId = -1
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}