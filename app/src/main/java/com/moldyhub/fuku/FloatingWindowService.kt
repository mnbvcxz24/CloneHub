package com.moldyhub.fuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns every floating Fuku window. Runs as a foreground service so Android keeps the windows (and
 * the virtual displays behind them) alive while Fuku itself is in the background.
 */
class FloatingWindowService : Service() {
    companion object {
        private const val ACTION_OPEN = "com.moldyhub.fuku.OPEN_WINDOW"
        private const val ACTION_CLOSE_ALL = "com.moldyhub.fuku.CLOSE_ALL_WINDOWS"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_SLOT = "slot"
        private const val CHANNEL_ID = "fuku_windows"
        private const val NOTIFICATION_ID = 7001

        fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

        fun open(context: Context, packageName: String, slot: Int) {
            val intent = Intent(context, FloatingWindowService::class.java)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_SLOT, slot)
            context.startForegroundService(intent)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val windows = LinkedHashMap<String, FloatingCloneWindow>()
    private var worker: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var bridge: RootInputBridge? = null
    private var rootChecked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_OPEN -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                if (pkg != null) openWindow(pkg, intent.getIntExtra(EXTRA_SLOT, windows.size))
            }
            ACTION_CLOSE_ALL -> closeAll()
        }
        if (windows.isEmpty() && !rootChecked) stopSelfIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        windows.values.toList().forEach { it.destroyNow() }
        windows.clear()
        bridge?.stop()
        bridge = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun openWindow(packageName: String, slot: Int) {
        windows[packageName]?.let {
            it.restore()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow \"Display over other apps\" for Fuku first.")
            stopSelfIfIdle()
            return
        }
        val clone = loadClone(packageName)
        if (clone == null) {
            toast("$packageName is not installed.")
            stopSelfIfIdle()
            return
        }

        // The first window checks for root and starts the touch helper off the main thread.
        rootChecked = true
        worker.execute {
            val ready = bridge?.start() == true || run {
                if (!RootShell.isAvailable()) {
                    main.post {
                        toast("Root access was denied or is unavailable, so Fuku windows can't open.")
                        stopSelfIfIdle()
                    }
                    return@execute
                }
                val created = RootInputBridge(applicationInfo.sourceDir)
                val started = created.start()
                // Assigned here, not posted, so a second tap can't create a second helper.
                if (started) bridge = created else created.stop()
                started
            }
            if (!ready) {
                main.post {
                    toast("Could not start the root touch helper.")
                    stopSelfIfIdle()
                }
                return@execute
            }
            main.post { showWindow(clone, slot) }
        }
    }

    private fun showWindow(clone: InstalledClone, slot: Int) {
        val activeBridge = bridge
        if (activeBridge == null || windows.containsKey(clone.packageName)) {
            stopSelfIfIdle()
            return
        }
        try {
            val window = FloatingCloneWindow(
                context = this,
                clone = clone,
                slot = slot,
                input = activeBridge,
                worker = worker,
                onClosed = { pkg ->
                    windows.remove(pkg)
                    stopSelfIfIdle()
                }
            )
            windows[clone.packageName] = window
            window.show()
        } catch (e: Exception) {
            windows.remove(clone.packageName)
            toast("Could not open the window: ${e.message ?: e.javaClass.simpleName}")
            stopSelfIfIdle()
        }
    }

    private fun closeAll() {
        windows.values.toList().forEach { it.close() }
    }

    private fun stopSelfIfIdle() {
        if (windows.isEmpty()) {
            bridge?.stop()
            bridge = null
            rootChecked = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun loadClone(packageName: String): InstalledClone? = try {
        val pm = packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        InstalledClone(pm.getApplicationLabel(info).toString(), packageName, pm.getApplicationIcon(info))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fuku windows", NotificationManager.IMPORTANCE_LOW)
        )
        val closeAll = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingWindowService::class.java).setAction(ACTION_CLOSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fuku windows are open")
            .setContentText("Tap Close all to stop every window and its clone.")
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Close all",
                    closeAll
                ).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}