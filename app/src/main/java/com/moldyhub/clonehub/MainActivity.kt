package com.moldyhub.clonehub

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * CloneHub V1 library shell.
 *
 * APKs are copied into app-private storage after selection so imports remain
 * available even when a document provider grants only temporary URI access.
 * This screen does not execute imported APKs; virtual execution is a separate
 * runtime milestone.
 */
class MainActivity : Activity() {

    private lateinit var libraryContainer: LinearLayout
    private lateinit var importStatus: TextView
    private lateinit var importButton: Button
    private lateinit var alternateImportButton: Button

    private val importExecutor = Executors.newSingleThreadExecutor()

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
            setPadding(0, dp(4), 0, dp(16))
        }

        importButton = Button(this).apply {
            text = "Import APK"
            isAllCaps = false
            setOnClickListener { openApkPicker(REQUEST_OPEN_DOCUMENT) }
        }

        alternateImportButton = Button(this).apply {
            text = "Use alternate picker"
            isAllCaps = false
            setOnClickListener { openApkPicker(REQUEST_GET_CONTENT) }
        }

        importStatus = TextView(this).apply {
            text = "If the device's document picker fails, try the alternate picker."
            textSize = 12f
            setTextColor(0xFF777783.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }

        val sectionTitle = TextView(this).apply {
            text = "Imported apps"
            textSize = 18f
            setTextColor(0xFF17171C.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(12))
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
        root.addView(alternateImportButton)
        root.addView(importStatus)
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

    private fun openApkPicker(requestCode: Int) {
        val action = if (requestCode == REQUEST_OPEN_DOCUMENT) {
            Intent.ACTION_OPEN_DOCUMENT
        } else {
            Intent.ACTION_GET_CONTENT
        }

        val intent = Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = APK_MIME_TYPE
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (requestCode == REQUEST_OPEN_DOCUMENT) {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }

        try {
            startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            if (requestCode == REQUEST_OPEN_DOCUMENT) {
                openApkPicker(REQUEST_GET_CONTENT)
            } else {
                showPickerError()
            }
        } catch (_: SecurityException) {
            if (requestCode == REQUEST_OPEN_DOCUMENT) {
                openApkPicker(REQUEST_GET_CONTENT)
            } else {
                showPickerError()
            }
        } catch (_: RuntimeException) {
            if (requestCode == REQUEST_OPEN_DOCUMENT) {
                openApkPicker(REQUEST_GET_CONTENT)
            } else {
                showPickerError()
            }
        }
    }

    @Deprecated("Uses request codes to support the existing project baseline.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_DOCUMENT && requestCode != REQUEST_GET_CONTENT) return
        if (resultCode != RESULT_OK) return

        val uri = data?.data
        if (uri == null) {
            Toast.makeText(this, "The picker did not return a file.", Toast.LENGTH_SHORT).show()
            return
        }

        setImporting(true)
        importStatus.text = "Importing APK into CloneHub storage…"

        importExecutor.execute {
            try {
                val displayName = queryDisplayName(uri) ?: "Imported APK"
                val importedFile = copyIntoPrivateStorage(uri, displayName)
                val entries = readEntries().toMutableList()
                if (entries.none { it.localPath == importedFile.absolutePath }) {
                    entries.add(
                        LibraryEntry(
                            name = displayName,
                            uri = uri.toString(),
                            localPath = importedFile.absolutePath
                        )
                    )
                    writeEntries(entries)
                }

                runOnUiThread {
                    setImporting(false)
                    importStatus.text = "APK copied safely into CloneHub storage."
                    renderLibrary()
                    Toast.makeText(this, "APK added to library", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setImporting(false)
                    importStatus.text = "Import failed. Try the alternate picker or another file provider."
                    Toast.makeText(
                        this,
                        error.message?.let { "Could not import APK: $it" } ?: "Could not import APK.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setImporting(importing: Boolean) {
        importButton.isEnabled = !importing
        alternateImportButton.isEnabled = !importing
        importButton.text = if (importing) "Importing…" else "Import APK"
    }

    private fun copyIntoPrivateStorage(uri: Uri, displayName: String): File {
        val directory = File(filesDir, "imported_apks")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create CloneHub storage.")
        }

        val safeName = displayName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "imported.apk" }
        val finalName = if (safeName.endsWith(".apk", ignoreCase = true)) {
            safeName
        } else {
            "$safeName.apk"
        }
        val destination = File(directory, "${System.currentTimeMillis()}_$finalName")

        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("The selected provider could not open this file.")

        input.use { source ->
            FileOutputStream(destination).use { output ->
                source.copyTo(output)
                output.fd.sync()
            }
        }

        if (destination.length() == 0L) {
            destination.delete()
            throw IllegalStateException("The selected file was empty.")
        }
        return destination
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showPickerError() {
        importStatus.text = "No compatible file picker was found. Install or enable a file manager."
        Toast.makeText(this, "No compatible APK picker is available.", Toast.LENGTH_LONG).show()
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
                    add(
                        LibraryEntry(
                            name = item.getString("name"),
                            uri = item.optString("uri", ""),
                            localPath = item.optString("localPath").takeIf { it.isNotBlank() }
                        )
                    )
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
                    .put("localPath", entry.localPath)
            )
        }
        preferences.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        importExecutor.shutdown()
    }

    private data class LibraryEntry(
        val name: String,
        val uri: String,
        val localPath: String? = null
    )

    companion object {
        private const val REQUEST_OPEN_DOCUMENT = 1001
        private const val REQUEST_GET_CONTENT = 1002
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val KEY_ENTRIES = "entries"
    }
}
