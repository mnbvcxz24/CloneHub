package com.moldyhub.fuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

class CloneInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }

                if (confirmation == null) {
                    Log.e("FukuInstaller", "Installer requested approval but supplied no confirmation intent.")
                    Toast.makeText(
                        context,
                        "Android requires install approval, but did not provide its confirmation screen. Retry the clone.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }

                // This receiver is invoked by PackageInstaller's status callback.
                // FLAG_ACTIVITY_NEW_TASK is required because it is not an Activity.
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmation)
                } catch (error: Throwable) {
                    Log.e("FukuInstaller", "Could not open Android install confirmation.", error)
                    Toast.makeText(
                        context,
                        "Could not open Android's install approval screen: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(
                context, "Fuku clone installed successfully.", Toast.LENGTH_LONG
            ).show()

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "No error message provided by Android"
                val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val details = buildString {
                    append("Clone install failed. Status: ")
                    append(status)
                    if (!packageName.isNullOrBlank()) {
                        append(", package: ")
                        append(packageName)
                    }
                    append(". ")
                    append(message)
                }
                Toast.makeText(context, details, Toast.LENGTH_LONG).show()
            }
        }
    }
}
