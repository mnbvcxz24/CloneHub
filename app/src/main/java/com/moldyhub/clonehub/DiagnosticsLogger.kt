package com.moldyhub.clonehub

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persists diagnostic events so they survive CloneHub being closed by the host. */
internal object DiagnosticsLogger {
    private const val LOG_FILE_NAME = "clonehub-diagnostics.txt"
    private val lock = Any()
    @Volatile private var crashHandlerInstalled = false

    fun record(context: Context, message: String) {
        synchronized(lock) {
            runCatching {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(Date())
                File(context.filesDir, LOG_FILE_NAME).appendText("$timestamp  $message\n")
            }
        }
    }

    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        synchronized(this) {
            if (crashHandlerInstalled) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    val writer = StringWriter()
                    error.printStackTrace(PrintWriter(writer))
                    record(
                        appContext,
                        "FATAL uncaught exception on thread=${thread.name}:\n$writer"
                    )
                }
                previous?.uncaughtException(thread, error)
            }
            crashHandlerInstalled = true
            record(appContext, "Crash diagnostic handler installed")
        }
    }

    fun exportToDownloads(context: Context): Uri? {
        synchronized(lock) {
            val source = File(context.filesDir, LOG_FILE_NAME)
            if (!source.exists()) record(context, "Diagnostics export requested; log was newly created")

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, LOG_FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/CloneHub"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val destination = context.contentResolver.insert(collection, values) ?: return null
            return try {
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not open the diagnostics export file")

                val completed = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(destination, completed, null, null)
                destination
            } catch (error: Exception) {
                context.contentResolver.delete(destination, null, null)
                throw error
            }
        }
    }
}
