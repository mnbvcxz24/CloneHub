package com.moldyhub.clonehub
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties

private const val ROBLOX_PACKAGE = "com.roblox.client"
private const val CLONE_PREFIX = "$ROBLOX_PACKAGE.clonehub.c"
private data class RobloxClone(val packageName: String, val label: String, val icon: Drawable)

class MainActivity : ComponentActivity() {
    private var clones by mutableStateOf<List<RobloxClone>>(emptyList())
    private var sourcePaths by mutableStateOf<List<String>>(emptyList())
    private var sourceAvailable by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var pendingDelete by mutableStateOf<RobloxClone?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshManager()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FukuHome(clones, sourceAvailable, busy, message, ::addClone, ::launchClone) { pendingDelete = it }
                    pendingDelete?.let { clone ->
                        AlertDialog(
                            onDismissRequest = { pendingDelete = null },
                            title = { Text("Delete ${clone.label}?") },
                            text = { Text("Android will ask you to confirm uninstalling this instance. Android will remove its app data.") },
                            confirmButton = {
                                Button(onClick = {
                                    pendingDelete = null
                                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${clone.packageName}")))
                                }) { Text("Continue") }
                            },
                            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); refreshManager() }

    @Suppress("DEPRECATION")
    private fun refreshManager() {
        val source = runCatching { packageManager.getApplicationInfo(ROBLOX_PACKAGE, 0) }.getOrNull()
        sourceAvailable = source != null
        sourcePaths = source?.let { info -> buildList {
            add(info.sourceDir)
            info.splitSourceDirs?.let { addAll(it) }
        } } ?: emptyList()
        clones = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName.startsWith(CLONE_PREFIX) }
            .mapNotNull { info -> runCatching {
                RobloxClone(info.packageName, packageManager.getApplicationLabel(info).toString(),
                    packageManager.getApplicationIcon(info))
            }.getOrNull() }
            .sortedBy { it.packageName }
            .toList()
    }

    private fun launchClone(clone: RobloxClone) {
        val launch = packageManager.getLaunchIntentForPackage(clone.packageName)
        if (launch == null) message = "Could not find a launch activity for ${clone.label}."
        else startActivity(launch)
    }

    private fun addClone() {
        if (!sourceAvailable || busy) return
        busy = true
        message = "Preparing app files..."
        Thread {
            val result = runCatching {
                val workspace = File(filesDir, "prepared_apks/$ROBLOX_PACKAGE")
                if (workspace.exists() && !workspace.deleteRecursively()) error("Could not clear the previous preparation files.")
                if (!workspace.mkdirs() && !workspace.isDirectory) error("Could not create the private workspace.")
                val records = sourcePaths.mapIndexed { index, path ->
                    val input = File(path)
                    require(input.isFile && input.canRead()) { "The source APK file is unavailable." }
                    val name = if (index == 0) "base.apk" else "split_$index.apk"
                    val output = File(workspace, name)
                    input.inputStream().use { src -> output.outputStream().use { dst -> src.copyTo(dst) } }
                    val hash = sha256(output)
                    require(hash == sha256(input)) { "APK verification failed for $name." }
                    Triple(name, output.length(), hash)
                }
                val metadata = Properties().apply {
                    setProperty("packageName", ROBLOX_PACKAGE)
                    setProperty("appLabel", "Fuku")
                    setProperty("createdAtEpochMs", System.currentTimeMillis().toString())
                    setProperty("apkCount", records.size.toString())
                    records.forEachIndexed { index, record ->
                        setProperty("apk.$index.name", record.first)
                        setProperty("apk.$index.bytes", record.second.toString())
                        setProperty("apk.$index.sha256", record.third)
                    }
                }
                FileOutputStream(File(workspace, "workspace.properties")).use {
                    metadata.store(it, "Fuku source APK workspace")
                }
                val outcome = CloneApkBuilder(this).buildAndInstall(workspace.absolutePath, ROBLOX_PACKAGE, "Fuku")
                "Android is ready to install ${outcome.packageName}. Approve the system prompt to finish."
            }.getOrElse { error ->
                "Could not create instance: ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread { busy = false; message = result; refreshManager() }
        }.start()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@Composable
private fun FukuHome(
    clones: List<RobloxClone>, sourceAvailable: Boolean, busy: Boolean, message: String?,
    onAdd: () -> Unit, onLaunch: (RobloxClone) -> Unit, onDelete: (RobloxClone) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Fuku", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Your app instances", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INSTANCES", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${clones.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (clones.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No instances yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (sourceAvailable) "Add your first instance to get started."
                    else "Install the supported app on your device to get started.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(clones, key = { it.packageName }) { clone ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(clone.icon.toBitmap(96, 96).asImageBitmap(), null, Modifier.size(44.dp))
                            Column(Modifier.weight(1f)) {
                                Text(clone.label, fontWeight = FontWeight.SemiBold)
                                Text("Installed instance", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onLaunch(clone) }) { Text("Open") }
                            TextButton(onClick = { onDelete(clone) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        Button(onClick = onAdd, enabled = sourceAvailable && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Creating instance..." else "＋  Add instance")
        }
        if (!sourceAvailable) Text("The supported app is not installed. Install it first, then return to Fuku.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
