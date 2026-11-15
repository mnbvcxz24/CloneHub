package com.moldyhub.fuku.rootinput

import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Runs as root, outside the Fuku app process:
 *
 *   CLASSPATH=<Fuku base.apk> app_process /system/bin com.moldyhub.fuku.rootinput.InputServer
 *
 * It reads touch lines from stdin and injects them into the display they name. Only root (or the
 * shell) may inject input into another app's display, which is why this is a separate process.
 *
 * Line format (space separated):
 *   M <displayId> <action> <downTimeMs> <pointerCount> {<pointerId> <x> <y> <pressure>}...
 *
 * Keep this class and its main() when minifying (see proguard-rules.pro).
 */
object InputServer {
    private const val SOURCE_TOUCHSCREEN = 0x1002 // InputDevice.SOURCE_TOUCHSCREEN
    private const val INJECT_MODE_ASYNC = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val injector = try { Injector() } catch (t: Throwable) {
            System.err.println("InputServer startup failed: $t")
            return
        }
        System.out.println("InputServer ready")
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break // stdin closed: the app is gone
            try {
                injector.handle(line)
            } catch (t: Throwable) {
                System.err.println("InputServer error: $t")
            }
        }
    }

    private class Injector {
        private val manager: Any
        private val inject: Method
        private val setDisplayId: Method

        init {
            // Android 14+ moved the singleton to InputManagerGlobal.
            val managerClass = try {
                Class.forName("android.hardware.input.InputManagerGlobal")
            } catch (e: ClassNotFoundException) {
                Class.forName("android.hardware.input.InputManager")
            }
            manager = managerClass.getDeclaredMethod("getInstance").invoke(null)
                ?: error("No InputManager instance")
            inject = manager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            setDisplayId = InputEvent::class.java.getMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
        }

        fun handle(line: String) {
            val parts = line.trim().split(' ')
            if (parts.size < 5 || parts[0] != "M") return
            val displayId = parts[1].toInt()
            val action = parts[2].toInt()
            val downTime = parts[3].toLong()
            val count = parts[4].toInt()
            if (count < 1 || parts.size < 5 + count * 4) return

            val properties = Array(count) { MotionEvent.PointerProperties() }
            val coords = Array(count) { MotionEvent.PointerCoords() }
            for (i in 0 until count) {
                val base = 5 + i * 4
                properties[i].id = parts[base].toInt()
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER
                coords[i].x = parts[base + 1].toFloat()
                coords[i].y = parts[base + 2].toFloat()
                coords[i].pressure = parts[base + 3].toFloat()
                coords[i].size = 1f
            }

            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(
                minOf(downTime, now), now, action, count, properties, coords,
                0, 0, 1f, 1f, 0, 0, SOURCE_TOUCHSCREEN, 0
            )
            try {
                setDisplayId.invoke(event, displayId)
                val accepted = inject.invoke(manager, event, INJECT_MODE_ASYNC) as? Boolean
                if (accepted == false) System.err.println("InputManager rejected event for display $displayId")
            } finally {
                event.recycle()
            }
        }
    }
}