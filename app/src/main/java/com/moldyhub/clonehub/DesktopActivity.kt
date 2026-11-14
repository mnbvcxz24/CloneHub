package com.moldyhub.clonehub

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

/**
 * CloneHub desktop-style shell inspired by Noka's floating-window presentation.
 * This is the launcher/window UI layer only; guest apps are not embedded in these windows yet.
 */
class DesktopActivity : Activity() {
    private lateinit var desktop: FrameLayout
    private lateinit var taskbar: LinearLayout
    private val openWindows = linkedMapOf<String, View>()
    private val windowTitles = linkedMapOf<String, String>()
    private var nextWindow = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(174, 216, 246)
        window.navigationBarColor = Color.rgb(27, 39, 57)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildDesktop()
    }

    private fun buildDesktop() {
        desktop = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(157, 207, 243), Color.rgb(210, 232, 249), Color.rgb(139, 190, 231))
            )
        }

        val top = TextView(this).apply {
            text = "CLONEHUB  •  DESKTOP"
            textSize = 10f
            letterSpacing = .12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 57, 79))
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        desktop.addView(top, FrameLayout.LayoutParams(-1, dp(32), Gravity.TOP))

        val appGrid = GridLayout(this).apply {
            columnCount = 4
            useDefaultMargins = false
            setPadding(dp(10), dp(40), dp(10), dp(58))
        }
        val apps = launcherApps().take(20)
        apps.forEach { info ->
            val app = info.activityInfo.applicationInfo
            val label = packageManager.getApplicationLabel(app).toString()
            val icon = try { packageManager.getApplicationIcon(app) } catch (_: Exception) { null }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { showAppWindow(app.packageName, label, icon) }
            }
            val image = ImageView(this).apply {
                if (icon != null) setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(image, LinearLayout.LayoutParams(dp(42), dp(42)))
            tile.addView(TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(Color.rgb(25, 43, 61))
                setShadowLayer(2f, 0f, 1f, Color.WHITE)
                setPadding(0, dp(4), 0, 0)
            })
            appGrid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(82)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        desktop.addView(appGrid, FrameLayout.LayoutParams(-1, -1))

        taskbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = solid(Color.rgb(30, 43, 60), 14)
        }
        val home = TextView(this).apply {
            text = " ◈ "
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { showHubWindow() }
        }
        taskbar.addView(home, LinearLayout.LayoutParams(dp(48), dp(42)))
        taskbar.addView(View(this).apply { setBackgroundColor(Color.rgb(83, 101, 123)) },
            LinearLayout.LayoutParams(dp(1), dp(26)))
        taskbar.addView(TextView(this).apply {
            text = "CloneHub"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { showHubWindow() }
        }, LinearLayout.LayoutParams(0, -1, 1f))
        taskbar.addView(TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(34), -1))
        desktop.addView(taskbar, FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM).apply {
            leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(8)
        })

        setContentView(desktop)
        showHubWindow()
    }

    private fun launcherApps(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { packageManager.getApplicationLabel(it.activityInfo.applicationInfo).toString().lowercase() }
    }

    private fun showHubWindow() {
        showWindow("hub", "CloneHub Manager") {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(16))
            }
            body.addView(TextView(this).apply {
                text = "Your apps, in a desktop-style space."
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            body.addView(TextView(this).apply {
                text = "Open the existing instance manager to create and manage your profiles. Floating guest-app rendering is a separate runtime step and is not connected yet."
                textSize = 12f
                setTextColor(Color.rgb(205, 208, 218))
                setPadding(0, dp(8), 0, dp(14))
            })
            body.addView(actionButton("Open instance manager") {
                startActivity(Intent(this, MainActivity::class.java))
            })
            body
        }
    }

    private fun showAppWindow(packageName: String, label: String, icon: android.graphics.drawable.Drawable?) {
        showWindow(packageName, label) {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(16), dp(18), dp(18))
            }
            if (icon != null) body.addView(ImageView(this).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(54), dp(54)))
            body.addView(TextView(this).apply {
                text = label
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, dp(4))
            })
            body.addView(TextView(this).apply {
                text = "Desktop window preview. This is not an embedded clone instance yet."
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(190, 195, 208))
            })
            body.addView(actionButton("Open normally") {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) startActivity(launch)
                else Toast.makeText(this, "No launch activity found.", Toast.LENGTH_SHORT).show()
            }.apply { setPadding(dp(12), 0, dp(12), 0) }, LinearLayout.LayoutParams(-1, dp(42)).apply {
                topMargin = dp(14)
            })
            body
        }
    }

    private fun showWindow(key: String, title: String, contentFactory: () -> View) {
        openWindows[key]?.let { desktop.removeView(it) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = solid(Color.rgb(36, 39, 49), 12)
            elevation = dp(18).toFloat()
            clipToOutline = true
        }
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            background = solid(Color.rgb(25, 27, 35), 12)
        }
        titleBar.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        titleBar.addView(windowControl("—") { panel.visibility = View.GONE })
        titleBar.addView(windowControl("□") {
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            val expanded = lp.width == -1
            lp.width = if (expanded) dp(330) else -1
            lp.height = if (expanded) dp(420) else -1
            lp.gravity = if (expanded) Gravity.CENTER else Gravity.FILL
            lp.leftMargin = 0; lp.topMargin = 0; lp.rightMargin = 0; lp.bottomMargin = 0
            panel.layoutParams = lp
        })
        titleBar.addView(windowControl("×") {
            desktop.removeView(panel)
            openWindows.remove(key)
            windowTitles.remove(key)
            refreshTaskbar()
        })
        panel.addView(titleBar)
        panel.addView(contentFactory(), LinearLayout.LayoutParams(-1, -2))
        titleBar.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f
            var startX = 0f; var startY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val lp = panel.layoutParams as FrameLayout.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startX = lp.leftMargin.toFloat(); startY = lp.topMargin.toFloat()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (lp.gravity == Gravity.FILL) return true
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.leftMargin = (startX + event.rawX - downX).toInt().coerceIn(0, max(0, desktop.width - panel.width))
                        lp.topMargin = (startY + event.rawY - downY).toInt().coerceIn(dp(28), max(dp(28), desktop.height - panel.height - dp(60)))
                        panel.layoutParams = lp
                        return true
                    }
                }
                return true
            }
        })
        val params = FrameLayout.LayoutParams(dp(330), dp(300), Gravity.CENTER)
        desktop.addView(panel, params)
        panel.bringToFront()
        taskbar.bringToFront()
        openWindows[key] = panel
        windowTitles[key] = title
        refreshTaskbar()
    }

    private fun refreshTaskbar() {
        if (!::taskbar.isInitialized) return
        taskbar.removeViews(2, max(0, taskbar.childCount - 3))
        windowTitles.forEach { (key, title) ->
            val chip = TextView(this).apply {
                text = title.take(12)
                textSize = 10f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { openWindows[key]?.let { it.visibility = View.VISIBLE; it.bringToFront(); taskbar.bringToFront() } }
            }
            taskbar.addView(chip, LinearLayout.LayoutParams(-2, -1))
        }
    }

    private fun windowControl(symbol: String, action: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(225, 228, 236))
        setOnClickListener { action() }
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(42))
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        background = solid(Color.rgb(74, 112, 190), 9)
        setOnClickListener { action() }
    }

    private fun solid(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
