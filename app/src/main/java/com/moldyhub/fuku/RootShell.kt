package com.moldyhub.fuku

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs one-off commands as root through the device's `su`. */
object RootShell {
    data class Result(val exitCode: Int, val output: String)

    /** Blocking. Call from a background thread. A root-grant prompt may take a while to answer. */
    fun run(command: String, timeoutSeconds: Long = 30): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            var output = ""
            val reader = thread(isDaemon = true) {
                output = process.inputStream.bufferedReader().readText()
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                return Result(-1, "Timed out waiting for root.")
            }
            reader.join(1000)
            Result(process.exitValue(), output.trim())
        } catch (e: Exception) {
            Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    fun isAvailable(): Boolean = run("id").output.contains("uid=0")
}