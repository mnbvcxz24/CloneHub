package com.moldyhub.clonehub

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

private data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val apkPaths: List<String>
)

class MainActivity : ComponentActivity() {
    private var installedApps by mutableStateOf<List<InstalledApp>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshApps()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CloneHubHome(
                        apps = installedApps,
                        onRefresh = ::refreshApps,
                        onPrepare = ::prepareApks
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
    }

    @Suppress("DEPRECATION")
    private fun refreshApps() {
        installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                val apkPaths = buildList {
                    add(info.sourceDir)
                    info.splitSourceDirs?.let { addAll(it) }
                }
                InstalledApp(
                    label = packageManager.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    icon = packageManager.getApplicationIcon(info),
                    apkPaths = apkPaths
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun prepareApks(app: InstalledApp, onComplete: (String) -> Unit) {
        Thread {
            val result = runCatching {
                val safePackage = app.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val workspace = File(filesDir, "prepared_apks/$safePackage")
                if (workspace.exists() && !workspace.deleteRecursively()) {
                    error("Could not clear the previous APK workspace.")
                }
                if (!workspace.mkdirs() && !workspace.isDirectory) {
                    error("Could not create the private APK workspace.")
                }

                var totalBytes = 0L
                val extractedFiles = app.apkPaths.mapIndexed { index, sourcePath ->
                    val source = File(sourcePath)
                    require(source.isFile && source.canRead()) {
                        "APK source is missing or unreadable: $sourcePath"
                    }

                    val name = if (index == 0) "base.apk" else "split_$index.apk"
                    val destination = File(workspace, name)
                    val temporary = File(workspace, "$name.tmp")
                    FileInputStream(source).use { input ->
                        FileOutputStream(temporary).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                            output.fd.sync()
                        }
                    }
                    if (destination.exists() && !destination.delete()) {
                        temporary.delete()
                        error("Could not replace $name.")
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.delete()
                        error("Could not finalize $name.")
                    }
                    totalBytes += destination.length()
                    destination.name
                }

                val sizeMb = totalBytes / (1024.0 * 1024.0)
                "Prepared ${extractedFiles.size} APK file(s) (${String.format(Locale.US, "%.1f", sizeMb)} MB).\nSaved in CloneHub private storage:\n${workspace.absolutePath}"
            }.getOrElse { error ->
                "Extraction failed: ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread { onComplete(result) }
        }.start()
    }
}

@Composable
private fun CloneHubHome(
    apps: List<InstalledApp>,
    onRefresh: () -> Unit,
    onPrepare: (InstalledApp, (String) -> Unit) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var preparingPackage by remember { mutableStateOf<String?>(null) }
    var preparationResult by remember { mutableStateOf<String?>(null) }
    val filteredApps = remember(apps, search) {
        apps.filter {
            it.label.contains(search, ignoreCase = true) ||
                it.packageName.contains(search, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("CloneHub", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Select an installed app to inspect or prepare its APK files.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            BasicTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                decorationBox = { innerTextField ->
                    if (search.isEmpty()) Text("Search installed apps", color = Color.Gray)
                    innerTextField()
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Installed apps", fontWeight = FontWeight.SemiBold)
                Text(
                    "${apps.size} launchable apps",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onRefresh) { Text("Refresh") }
        }
        if (filteredApps.isEmpty()) {
            Text(
                "No matching launchable apps found.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selectedApp = app
                            preparationResult = null
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val iconBitmap = remember(app.packageName) {
                                app.icon.toBitmap(width = 96, height = 96).asImageBitmap()
                            }
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = "${app.label} icon",
                                modifier = Modifier.size(44.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(app.label, fontWeight = FontWeight.Medium)
                                Text(
                                    app.packageName,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${app.apkPaths.size} APK file(s)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            "APK preparation only. Package modification, signing, and separate installation are not implemented yet.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    selectedApp?.let { app ->
        AlertDialog(
            onDismissRequest = {
                if (preparingPackage != app.packageName) selectedApp = null
            },
            title = { Text(app.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Package: ${app.packageName}")
                    Text("APK files found: ${app.apkPaths.size}")
                    app.apkPaths.forEachIndexed { index, path ->
                        Text(
                            "${if (index == 0) "Base APK" else "Split APK $index"}: $path",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    preparationResult?.let {
                        Text(it, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = preparingPackage == null,
                    onClick = {
                        preparingPackage = app.packageName
                        preparationResult = "Copying APK files into CloneHub private storage..."
                        onPrepare(app) { result ->
                            preparationResult = result
                            preparingPackage = null
                        }
                    }
                ) {
                    Text(if (preparingPackage == app.packageName) "Preparing..." else "Prepare APKs")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = preparingPackage != app.packageName,
                    onClick = { selectedApp = null }
                ) { Text("Close") }
            }
        )
    }
}
