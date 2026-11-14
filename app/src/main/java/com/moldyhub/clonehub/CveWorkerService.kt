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

/** Isolated CVE worker used for process-boundary diagnostics only. */
class CveWorkerService : Service() {
    private val messenger by lazy { Messenger(WorkerHandler()) }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private inner class WorkerHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                CveWorkerProtocol.MSG_PING -> replyHandshake(message.replyTo)
                else -> super.handleMessage(message)
            }
        }

        private fun replyHandshake(replyTo: Messenger?) {
            val response = Message.obtain(null, CveWorkerProtocol.MSG_PONG).apply {
                arg1 = CveWorkerProtocol.VERSION
                arg2 = Process.myUid()
                data = Bundle().apply {
                    putInt(CveWorkerProtocol.KEY_PROCESS_ID, Process.myPid())
                }
            }
            runCatching { replyTo?.send(response) }
        }
    }
}
