package com.moldyhub.clonehub

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Temporary diagnostic screen.
 * Keep this activity intentionally minimal to isolate startup crashes.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = TextView(this).apply {
            text = "CloneHub diagnostic screen\\n\\nIf you can see this, the basic Activity UI opens."
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        setContentView(message)
    }
}
