package com.moldyhub.fuku

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Receives PackageInstaller's status callback in an Activity context.
 * This avoids asking a background BroadcastReceiver to open the installer UI.
 */
class CloneInstallCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStatus(intent)
    }

    private fun handleStatus(callback: Intent?) {
        if (callback == null) {
            finish()
            return
        }

        when (callback.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    callback.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    callback.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }

                if (confirmation == null) {
                    Log.e(TAG, "PackageInstaller requested approval without a confirmation intent.")
                    Toast.makeText(
                        this,
                        "Android requested install approval but did not provide its confirmation screen.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return
                }

                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(confirmation)
                } catch (error: Throwable) {
                    Log.e(TAG, "Could not launch Android install confirmation.", error)
                    Toast.makeText(
                        this,
                        "Could not open Android install approval: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                finish()
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, "Fuku clone installed successfully.", Toast.LENGTH_LONG).show()
                finish()
            }

            else -> {
                val status = callback.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = callback.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Android did not provide an error message."
                Log.e(TAG, "Clone installation failed. Status=$status message=$message")
                Toast.makeText(
                    this,
                    "Clone install failed (status $status): $message",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "FukuInstaller"
    }
}
