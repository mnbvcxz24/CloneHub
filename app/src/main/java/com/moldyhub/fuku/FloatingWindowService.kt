package com.moldyhub.fuku

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Movable overlay launcher for separately installed Fuku clones. */
class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 31, 48))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(76, 91, 125))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Fuku • Floating clones"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(4), dp(6), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        }
        val close = Button(this).apply {
            text = "×"
            isAllCaps = false
            setOnClickListener { stopSelf() }
        }
        header.addView(title)
        header.addView(close)
        panel.addView(header)

        panel.addView(TextView(this).apply {
            text = "Tap a clone to open it. Drag the title to move this panel."
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        val cloneList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(cloneList) }
        panel.addView(scroll, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, dp(280)
        ))
        populateClones(cloneList)

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(100)
        }

        title.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        runCatching { windowManager.updateViewLayout(panel, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> return true
                }
                return false
            }
        })

        floatingView = panel
        windowManager.addView(panel, params)
    }

    private fun populateClones(container: LinearLayout) {
        val clones = packageManager.getInstalledApplications(0)
            .filter { info ->
                info.packageName.matches(Regex(".+\\.fuku\\.c[0-9]{3}$")) &&
                    packageManager.getLaunchIntentForPackage(info.packageName) != null
            }
            .sortedBy { it.packageName }

        if (clones.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No installed Fuku clones found yet."
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(6), dp(12), dp(6), dp(12))
            })
            return
        }

        clones.forEach { app ->
            val label = runCatching { app.loadLabel(packageManager).toString() }
                .getOrDefault(app.packageName)
            container.addView(Button(this).apply {
                text = "Open $label"
                isAllCaps = false
                setOnClickListener {
                    packageManager.getLaunchIntentForPackage(app.packageName)?.let { launch ->
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                    }
                }
            }, LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) })
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        floatingView?.let { view -> runCatching { windowManager.removeView(view) } }
        floatingView = null
        super.onDestroy()
    }
}
