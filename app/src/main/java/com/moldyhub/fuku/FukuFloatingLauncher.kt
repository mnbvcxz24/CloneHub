package com.moldyhub.fuku

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.luiisca.floating.views.CloseFloatConfig
import io.github.luiisca.floating.views.ExpandedFloatConfig
import io.github.luiisca.floating.views.FloatingViewsConfig
import io.github.luiisca.floating.views.MainFloatConfig
import io.github.luiisca.floating.views.helpers.FloatingViewsManager

private data class FloatingLaunchTarget(val label: String, val packageName: String)

fun startFukuFloatingLauncher(context: Context) {
    val config = FloatingViewsConfig(
        enableAnimations = true,
        main = MainFloatConfig(
            composable = {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("F", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
            }
        ),
        close = CloseFloatConfig(),
        expanded = ExpandedFloatConfig(
            dimAmount = 0.12f,
            composable = { close -> FloatingAppPanel(context, close) }
        )
    )
    FloatingViewsManager.setNotificationProperties(title = "Fuku floating launcher")
    FloatingViewsManager.startFloatServiceIfPermitted(context, config)
}

@Suppress("DEPRECATION")
@Composable
private fun FloatingAppPanel(context: Context, close: () -> Unit) {
    val targets = remember {
        context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { (it.flags and ApplicationInfo.FLAG_STOPPED) == 0 }
            .mapNotNull { info ->
                val launch = context.packageManager.getLaunchIntentForPackage(info.packageName)
                if (launch == null) null else FloatingLaunchTarget(
                    context.packageManager.getApplicationLabel(info).toString(),
                    info.packageName
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    Card(modifier = Modifier.fillMaxWidth(0.92f)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Fuku Floating", style = MaterialTheme.typography.titleLarge)
                    Text("Choose an installed app to open")
                }
                Button(onClick = close) { Text("Close") }
            }
            Spacer(Modifier.height(2.dp))
            if (targets.isEmpty()) {
                Text("No launchable apps were found.")
            } else {
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(targets, key = { it.packageName }) { target ->
                        Card(modifier = Modifier.fillMaxWidth(), onClick = {
                            val launch = context.packageManager.getLaunchIntentForPackage(target.packageName)
                            if (launch != null) {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(launch) }
                            }
                        }) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(target.label, style = MaterialTheme.typography.titleMedium)
                                Text(target.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Text(
                "This is a floating app launcher. Android still opens the selected app in its normal app window.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
