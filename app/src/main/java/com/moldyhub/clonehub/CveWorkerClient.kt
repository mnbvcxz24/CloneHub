package com.moldyhub.clonehub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process

/** Asynchronously verifies that the isolated CVE worker can start and answer IPC. */
class CveWorkerClient(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    sealed class Result {
        data class Ready(val handshake: CveWorkerHandshake) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun ping(timeoutMs: Long = 5_000L, callback: (Result) -> Unit) {
        require(timeoutMs > 0) { "Timeout must be positive." }
        var bound = false
        var finished = false
        var remoteMessenger: Messenger? = null

        lateinit var connection: ServiceConnection

        fun finish(result: Result) {
            if (finished) return
            finished = true
            mainHandler.removeCallbacksAndMessages(connection)
            if (bound) {
                runCatching { appContext.unbindService(connection) }
                bound = false
            }
            callback(result)
        }

        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what != CveWorkerProtocol.MSG_PONG) return@Handler true
            val handshake = CveWorkerHandshake(
                protocolVersion = message.arg1,
                processId = message.data.getInt(CveWorkerProtocol.KEY_PROCESS_ID, -1),
                workerUid = message.arg2
            )
            if (handshake.isIsolatedFrom(Process.myUid())) {
                finish(Result.Ready(handshake))
            } else {
                finish(Result.Failed("Worker responded, but its isolated UID could not be verified."))
            }
            true
        })

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (finished || binder == null) {
                    finish(Result.Failed("CVE worker connected without a valid IPC binder."))
                    return
                }
                remoteMessenger = Messenger(binder)
                val request = Message.obtain(null, CveWorkerProtocol.MSG_PING).apply {
                    replyTo = replyMessenger
                }
                try {
                    remoteMessenger?.send(request)
                } catch (error: Exception) {
                    finish(Result.Failed(error.message ?: "Could not contact CVE worker."))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!finished) finish(Result.Failed("CVE worker process disconnected."))
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!finished) finish(Result.Failed("CVE worker binding died."))
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!finished) finish(Result.Failed("CVE worker returned an empty binding."))
            }
        }

        mainHandler.postAtTime(
            { finish(Result.Failed("CVE worker did not respond within ${timeoutMs}ms.")) },
            connection,
            android.os.SystemClock.uptimeMillis() + timeoutMs
        )

        try {
            bound = appContext.bindService(
                Intent(appContext, CveWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) finish(Result.Failed("Android refused to bind the CVE worker service."))
        } catch (error: Exception) {
            finish(Result.Failed(error.message ?: "Could not bind the CVE worker service."))
        }
    }
}
