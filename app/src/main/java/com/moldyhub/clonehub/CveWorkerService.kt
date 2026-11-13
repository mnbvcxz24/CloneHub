package com.moldyhub.clonehub

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process

/**
 * First CVE worker-process milestone.
 *
 * Android runs this service under an isolated UID. It is only an engine worker
 * handshake at this stage; it does not load or execute third-party app code.
 */
class CveWorkerService : Service() {
    private val messenger by lazy {
        Messenger(WorkerHandler())
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private inner class WorkerHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != CveWorkerProtocol.MSG_PING) {
                super.handleMessage(message)
                return
            }

            val response = Message.obtain(null, CveWorkerProtocol.MSG_PONG).apply {
                arg1 = CveWorkerProtocol.VERSION
                arg2 = Process.myUid()
                data = Bundle().apply { putInt(CveWorkerProtocol.KEY_PROCESS_ID, Process.myPid()) }
            }
            runCatching { message.replyTo?.send(response) }
        }
    }
}
