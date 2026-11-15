package com.moldyhub.fuku

import android.util.Log
import android.view.MotionEvent
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Keeps one long-lived root process running [com.moldyhub.fuku.rootinput.InputServer] and sends
 * it touch events for the clones' virtual displays.
 */
class RootInputBridge(private val apkPath: String) {
    private var process: Process? = null
    private var output: OutputStream? = null
    private val sender = Executors.newSingleThreadExecutor()

    /** Blocking (may show the root-grant prompt). Call from a background thread. */
    @Synchronized
    fun start(): Boolean {
        if (process?.isAlive == true) return true
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            val ready = java.util.concurrent.CountDownLatch(1)
            thread(isDaemon = true, name = "fuku-root-input-log") {
                runCatching { p.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, line)
                    if (line.contains("InputServer ready")) ready.countDown()
                } }
            }
            val stream = p.outputStream
            stream.write(
                ("CLASSPATH='$apkPath' exec app_process /system/bin " +
                    "com.moldyhub.fuku.rootinput.InputServer\n").toByteArray()
            )
            stream.flush()
            if (!ready.await(10, java.util.concurrent.TimeUnit.SECONDS) || !p.isAlive) {
                runCatching { stream.close() }
                p.destroy()
                Log.w(TAG, "Root input helper did not become ready")
                return false
            }
            process = p
            output = stream
            true
        } catch (e: IOException) {
            Log.w(TAG, "Could not start root input helper", e)
            false
        }
    }

    /** Scales a touch from the window's pixels to the virtual display's pixels and forwards it. */
    fun sendTouch(displayId: Int, event: MotionEvent, scaleX: Float, scaleY: Float) {
        val line = StringBuilder("M ")
            .append(displayId).append(' ')
            .append(event.action).append(' ')
            .append(event.downTime).append(' ')
            .append(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            line.append(' ').append(event.getPointerId(i))
                .append(' ').append(event.getX(i) * scaleX)
                .append(' ').append(event.getY(i) * scaleY)
                .append(' ').append(event.getPressure(i))
        }
        line.append('\n')
        val bytes = line.toString().toByteArray()
        sender.execute {
            try {
                output?.let {
                    it.write(bytes)
                    it.flush()
                }
            } catch (_: IOException) {
                // Helper died; the next window action will restart the bridge.
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { output?.close() } // EOF on stdin ends the helper
        process?.destroy()
        process = null
        output = null
        sender.shutdownNow()
    }

    private companion object {
        const val TAG = "FukuRootInput"
    }
}