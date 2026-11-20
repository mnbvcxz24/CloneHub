package com.moldyhub.fuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
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
