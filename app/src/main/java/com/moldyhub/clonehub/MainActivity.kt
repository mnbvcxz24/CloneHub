package com.moldyhub.clonehub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * CloneHub V1 shell.
 *
 * This screen imports APK files into the app library. It does not install,
 * modify, or execute imported APKs. Virtual execution will be added only
 * after the isolated runtime has been implemented and tested.
 */
class MainActivity : Activity() {

    private lateinit var libraryContainer: LinearLayout
    private val preferences by lazy {
        getSharedPreferences("clonehub_library", MODE_PRIVATE)
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
            text = "Your virtual app library"
            textSize = 14f
            setTextColor(0xFF666672.toInt())
            setPadding(0, dp(4), 0, dp(20))
        }

        val importButton = Button(this).apply {
            text = "Import APK"
            isAllCaps = false
            setOnClickListener { openApkPicker() }
        }

        val sectionTitle = TextView(this).apply {
            text = "Imported apps"
            textSize = 18f
            setTextColor(0xFF17171C.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(24), 0, dp(12))
        }

        libraryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(this).apply {
            addView(
                libraryContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(importButton)
        root.addView(sectionTitle)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        renderLibrary()
    }

    private fun openApkPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_APK)
    }

    @Deprecated("Kept compatible with the initial minSdk baseline.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_APK || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val takeFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: SecurityException) {
            // Some document providers grant only a temporary read permission.
        }

        val displayName = queryDisplayName(uri) ?: "Imported APK"
        val existing = readEntries().toMutableList()
        if (existing.none { it.uri == uri.toString() }) {
            existing.add(LibraryEntry(displayName, uri.toString()))
            writeEntries(existing)
        }

        renderLibrary()
        Toast.makeText(this, "APK added to library", Toast.LENGTH_SHORT).show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return null
    }

    private fun renderLibrary() {
        libraryContainer.removeAllViews()
        val entries = readEntries()

        if (entries.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No apps imported yet. Select an APK to get started."
                textSize = 14f
                setTextColor(0xFF777783.toInt())
                setPadding(dp(16), dp(18), dp(16), dp(18))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            libraryContainer.addView(
                empty,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
            return
        }

        entries.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }

            val name = TextView(this).apply {
                text = entry.name
                textSize = 16f
                setTextColor(0xFF202027.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val status = TextView(this).apply {
                text = "Imported • Runtime not available in this build"
                textSize = 12f
                setTextColor(0xFF777783.toInt())
                setPadding(0, dp(5), 0, 0)
            }

            card.addView(name)
            card.addView(status)
            libraryContainer.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
        }
    }

    private fun readEntries(): List<LibraryEntry> {
        val raw = preferences.getString(KEY_ENTRIES, "[]") ?: "[]"
        return try {
            val array = org.json.JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(LibraryEntry(item.getString("name"), item.getString("uri")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeEntries(entries: List<LibraryEntry>) {
        val array = org.json.JSONArray()
        entries.forEach { entry ->
            array.put(
                org.json.JSONObject()
                    .put("name", entry.name)
                    .put("uri", entry.uri)
            )
        }
        preferences.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class LibraryEntry(val name: String, val uri: String)

    companion object {
        private const val REQUEST_IMPORT_APK = 1001
        private const val KEY_ENTRIES = "entries"
    }
}
