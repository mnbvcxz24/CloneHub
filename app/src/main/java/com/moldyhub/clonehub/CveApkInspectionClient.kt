package com.moldyhub.clonehub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.File

/** Sends a read-only APK file descriptor to the isolated worker and receives its scan report. */
class CveApkInspectionClient(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    sealed class Result {
        data class Success(
            val packageName: String,
            val workerPid: Int,
            val workerUid: Int,
            val report: CveApkInspectionReport
        ) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun inspect(
        packageName: String,
        apkPath: String,
        timeoutMs: Long = 30_000L,
        callback: (Result) -> Unit
    ) {
        if (packageName.isBlank() || apkPath.isBlank()) {
            callback(Result.Failed("Package name or APK path is empty."))
            return
        }
        require(timeoutMs > 0) { "Timeout must be positive." }

        val apkFile = File(apkPath)
        if (!apkFile.isFile || !apkFile.canRead()) {
            callback(Result.Failed("CloneHub cannot read the selected APK file."))
            return
        }
        val descriptor = try {
            ParcelFileDescriptor.open(apkFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Exception) {
            callback(Result.Failed(error.message ?: "Could not open APK read-only."))
            return
        }

        var bound = false
        var finished = false
        lateinit var connection: ServiceConnection

        fun finish(result: Result) {
            if (finished) return
            finished = true
            mainHandler.removeCallbacksAndMessages(connection)
            if (bound) {
                runCatching { appContext.unbindService(connection) }
                bound = false
            }
            runCatching { descriptor.close() }
            callback(result)
        }

        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what != CveWorkerProtocol.MSG_APK_INSPECTED) return@Handler true
            val data = message.data
            val uid = data.getInt(CveWorkerProtocol.KEY_WORKER_UID, -1)
            val pid = data.getInt(CveWorkerProtocol.KEY_PROCESS_ID, -1)
            if (uid == Process.myUid() || uid < 0 || pid <= 0) {
                finish(Result.Failed("APK scan reply did not come from a verified isolated worker."))
            } else if (!data.getBoolean(CveWorkerProtocol.KEY_SCAN_OK, false)) {
                finish(Result.Failed(data.getString(CveWorkerProtocol.KEY_ERROR) ?: "Worker APK scan failed."))
            } else {
                finish(Result.Success(
                    packageName = packageName,
                    workerPid = pid,
                    workerUid = uid,
                    report = CveApkInspectionReport(
                        sha256 = data.getString(CveWorkerProtocol.KEY_SHA256).orEmpty(),
                        bytesRead = data.getLong(CveWorkerProtocol.KEY_BYTES_READ),
                        entryCount = data.getInt(CveWorkerProtocol.KEY_ENTRY_COUNT),
                        hasManifest = data.getBoolean(CveWorkerProtocol.KEY_HAS_MANIFEST),
                        dexFileCount = data.getInt(CveWorkerProtocol.KEY_DEX_COUNT),
                        nativeAbis = data.getStringArrayList(CveWorkerProtocol.KEY_NATIVE_ABIS).orEmpty()
                    )
                ))
            }
            true
        })

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                if (finished || binder == null) {
                    finish(Result.Failed("Isolated worker connection was invalid."))
                    return
                }
                val transferredDescriptor = try {
                    ParcelFileDescriptor.dup(descriptor.fileDescriptor)
                } catch (error: Exception) {
                    finish(Result.Failed(error.message ?: "Could not duplicate the APK descriptor."))
                    return
                }
                val request = Message.obtain(null, CveWorkerProtocol.MSG_INSPECT_APK).apply {
                    replyTo = replyMessenger
                    data = android.os.Bundle().apply {
                        putString(CveWorkerProtocol.KEY_PACKAGE_NAME, packageName)
                        putParcelable(CveWorkerProtocol.KEY_APK_FD, transferredDescriptor)
                    }
                }
                try {
                    Messenger(binder).send(request)
                    transferredDescriptor.close()
                } catch (error: Exception) {
                    runCatching { transferredDescriptor.close() }
                    finish(Result.Failed(error.message ?: "Could not send APK descriptor to worker."))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!finished) finish(Result.Failed("Isolated worker disconnected during APK scan."))
            }
            override fun onBindingDied(name: ComponentName?) {
                if (!finished) finish(Result.Failed("Isolated worker binding died during APK scan."))
            }
            override fun onNullBinding(name: ComponentName?) {
                if (!finished) finish(Result.Failed("Isolated worker returned an empty binding."))
            }
        }

        mainHandler.postAtTime(
            { finish(Result.Failed("Isolated APK scan timed out after ${timeoutMs}ms.")) },
            connection,
            SystemClock.uptimeMillis() + timeoutMs
        )
        try {
            bound = appContext.bindService(
                Intent(appContext, CveWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) finish(Result.Failed("Android refused to bind the isolated worker."))
        } catch (error: Exception) {
            finish(Result.Failed(error.message ?: "Could not bind the isolated worker."))
        }
    }
}
