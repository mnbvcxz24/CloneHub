package com.moldyhub.clonehub

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.util.zip.ZipException

/**
 * Runs under Android's isolated UID. It answers handshakes and performs read-only
 * APK archive inspection only; it never loads or executes target-app code.
 */
class CveWorkerService : Service() {
    private val messenger by lazy { Messenger(WorkerHandler()) }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private inner class WorkerHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                CveWorkerProtocol.MSG_PING -> replyHandshake(message)
                CveWorkerProtocol.MSG_INSPECT_APK -> inspectApk(message)
                else -> super.handleMessage(message)
            }
        }

        private fun replyHandshake(message: Message) {
            val response = Message.obtain(null, CveWorkerProtocol.MSG_PONG).apply {
                arg1 = CveWorkerProtocol.VERSION
                arg2 = Process.myUid()
                data = Bundle().apply {
                    putInt(CveWorkerProtocol.KEY_PROCESS_ID, Process.myPid())
                }
            }
            runCatching { message.replyTo?.send(response) }
        }

        private fun inspectApk(message: Message) {
            val request = message.data
            val packageName = request.getString(CveWorkerProtocol.KEY_PACKAGE_NAME).orEmpty()
            val descriptor = if (Build.VERSION.SDK_INT >= 33) {
                request.getParcelable(CveWorkerProtocol.KEY_APK_FD, ParcelFileDescriptor::class.java)
            } else {
                @Suppress("DEPRECATION")
                request.getParcelable<ParcelFileDescriptor>(CveWorkerProtocol.KEY_APK_FD)
            }

            val responseData = Bundle().apply {
                putInt(CveWorkerProtocol.KEY_PROCESS_ID, Process.myPid())
                putInt(CveWorkerProtocol.KEY_WORKER_UID, Process.myUid())
                putString(CveWorkerProtocol.KEY_PACKAGE_NAME, packageName)
            }

            try {
                require(packageName.isNotBlank()) { "Package name was missing." }
                requireNotNull(descriptor) { "Read-only APK file descriptor was missing." }
                val report = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                    CveApkArchiveInspector.inspect(it, cacheDir)
                }
                responseData.putBoolean(CveWorkerProtocol.KEY_SCAN_OK, true)
                responseData.putString(CveWorkerProtocol.KEY_SHA256, report.sha256)
                responseData.putLong(CveWorkerProtocol.KEY_BYTES_READ, report.bytesRead)
                responseData.putInt(CveWorkerProtocol.KEY_ENTRY_COUNT, report.entryCount)
                responseData.putBoolean(CveWorkerProtocol.KEY_HAS_MANIFEST, report.hasManifest)
                responseData.putInt(CveWorkerProtocol.KEY_DEX_COUNT, report.dexFileCount)
                responseData.putStringArrayList(
                    CveWorkerProtocol.KEY_NATIVE_ABIS,
                    ArrayList(report.nativeAbis)
                )
            } catch (error: Exception) {
                runCatching { descriptor?.close() }
                responseData.putBoolean(CveWorkerProtocol.KEY_SCAN_OK, false)
                responseData.putString(
                    CveWorkerProtocol.KEY_ERROR,
                    error.message ?: "APK archive inspection failed."
                )
            }

            val response = Message.obtain(null, CveWorkerProtocol.MSG_APK_INSPECTED).apply {
                data = responseData
            }
            runCatching { message.replyTo?.send(response) }
        }
    }
}
