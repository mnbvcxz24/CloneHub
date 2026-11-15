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
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

private data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val apkPaths: List<String>
)

private data class PreparedWorkspace(
    val packageName: String,
    val appLabel: String,
    val directory: String,
    val apkCount: Int,
    val totalBytes: Long,
    val createdAt: Long,
    val filesValid: Boolean
)

class MainActivity : ComponentActivity() {
    private var installedApps by mutableStateOf<List<InstalledApp>>(emptyList())
    private var preparedWorkspaces by mutableStateOf<List<PreparedWorkspace>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshApps()
        refreshWorkspaces()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CloneHubHome(
                        apps = installedApps,
                        workspaces = preparedWorkspaces,
                        onRefresh = {
                            refreshApps()
                            refreshWorkspaces()
                        },
                        onPrepare = ::prepareApks,
                        onAnalyze = ::analyzeWorkspace,
                        onClone = ::cloneWorkspace
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        refreshWorkspaces()
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

    private fun refreshWorkspaces() {
        val root = File(filesDir, "prepared_apks")
        preparedWorkspaces = root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { directory ->
                runCatching {
                    val metadataFile = File(directory, "workspace.properties")
                    require(metadataFile.isFile) { "Workspace metadata is missing." }
                    val metadata = Properties()
                    FileInputStream(metadataFile).use(metadata::load)
                    val packageName = metadata.getProperty("packageName") ?: return@runCatching null
                    val count = metadata.getProperty("apkCount")?.toIntOrNull() ?: 0
                    val records = (0 until count).map { index ->
                        val name = metadata.getProperty("apk.$index.name")
                            ?: error("APK filename is missing.")
                        val bytes = metadata.getProperty("apk.$index.bytes")?.toLongOrNull()
                            ?: error("APK size is missing.")
                        val hash = metadata.getProperty("apk.$index.sha256")
                            ?: error("APK checksum is missing.")
                        Triple(File(directory, name), bytes, hash)
                    }
                    val valid = records.isNotEmpty() && records.all { (file, bytes, _) ->
                        file.isFile && file.length() == bytes
                    }
                    PreparedWorkspace(
                        packageName = packageName,
                        appLabel = metadata.getProperty("appLabel") ?: packageName,
                        directory = directory.absolutePath,
                        apkCount = count,
                        totalBytes = records.sumOf { it.second },
                        createdAt = metadata.getProperty("createdAtEpochMs")?.toLongOrNull() ?: 0L,
                        filesValid = valid
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?.toList()
            ?: emptyList()
    }

    private data class ApkCopyRecord(val name: String, val bytes: Long, val sha256: String)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cloneWorkspace(workspace: PreparedWorkspace, onComplete: (String) -> Unit) {
        Thread {
            val result = runCatching {
                val outcome = CloneApkBuilder(this).buildAndInstall(workspace.directory, workspace.packageName, workspace.appLabel)
                "Built ${outcome.packageName}. Android's installer session is ready; approve the install prompt if it appears."
            }.getOrElse { error ->
                "Clone build failed: ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread { onComplete(result) }
        }.start()
    }

    private fun analyzeWorkspace(workspace: PreparedWorkspace, onComplete: (String) -> Unit) {
        Thread {
            val report = runCatching {
                val directory = File(workspace.directory)
                val metadata = Properties()
                FileInputStream(File(directory, "workspace.properties")).use { metadata.load(it) }
                val count = metadata.getProperty("apkCount")?.toIntOrNull()
                    ?: error("Workspace APK count is invalid.")
                val apkFiles = (0 until count).map { index ->
                    val name = metadata.getProperty("apk.$index.name")
                        ?: error("APK filename is missing.")
                    File(directory, name).also { require(it.isFile) { "Missing APK: $name" } }
                }
                ApkInspector.inspect(packageManager, apkFiles).toDisplayText()
            }.getOrElse { error ->
                "APK analysis failed: ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread { onComplete(report) }
        }.start()
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
                    val copiedHash = sha256(destination)
                    val sourceHash = sha256(source)
                    require(copiedHash == sourceHash) {
                        "Integrity check failed for ${destination.name}."
                    }
                    totalBytes += destination.length()
                    ApkCopyRecord(destination.name, destination.length(), copiedHash)
                }

                val metadata = Properties().apply {
                    setProperty("packageName", app.packageName)
                    setProperty("appLabel", app.label)
                    setProperty("createdAtEpochMs", System.currentTimeMillis().toString())
                    setProperty("apkCount", extractedFiles.size.toString())
                    extractedFiles.forEachIndexed { index, record ->
                        setProperty("apk.$index.name", record.name)
                        setProperty("apk.$index.bytes", record.bytes.toString())
                        setProperty("apk.$index.sha256", record.sha256)
                    }
                }
                val metadataTemp = File(workspace, "workspace.properties.tmp")
                val metadataFile = File(workspace, "workspace.properties")
                FileOutputStream(metadataTemp).use { output ->
                    metadata.store(output, "CloneHub prepared APK workspace")
                    output.fd.sync()
                }
                if (!metadataTemp.renameTo(metadataFile)) {
                    metadataTemp.delete()
                    error("Could not save APK workspace metadata.")
                }

                val sizeMb = totalBytes / (1024.0 * 1024.0)
                "Prepared and verified ${extractedFiles.size} APK file(s) (${String.format(Locale.US, "%.1f", sizeMb)} MB).\nSHA-256 checks passed for every file.\nWorkspace metadata saved."
            }.getOrElse { error ->
                "Extraction failed: ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread {
                refreshWorkspaces()
                onComplete(result)
            }
        }.start()
    }
}

@Composable
private fun CloneHubHome(
    apps: List<InstalledApp>,
    workspaces: List<PreparedWorkspace>,
    onRefresh: () -> Unit,
    onPrepare: (InstalledApp, (String) -> Unit) -> Unit,
    onAnalyze: (PreparedWorkspace, (String) -> Unit) -> Unit,
    onClone: (PreparedWorkspace, (String) -> Unit) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var preparingPackage by remember { mutableStateOf<String?>(null) }
    var preparationResult by remember { mutableStateOf<String?>(null) }
    var selectedWorkspace by remember { mutableStateOf<PreparedWorkspace?>(null) }
    var analysisResult by remember { mutableStateOf<String?>(null) }
    var analyzingPackage by remember { mutableStateOf<String?>(null) }
    var cloningPackage by remember { mutableStateOf<String?>(null) }
    var cloneResult by remember { mutableStateOf<String?>(null) }
    val filteredApps = remember(apps, search) {
        apps.filter {
            it.label.contains(search, ignoreCase = true) ||
                it.packageName.contains(search, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("CloneHub", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Select an installed app to inspect or prepare its APK files.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                Text("Prepared workspaces", fontWeight = FontWeight.SemiBold)
                Text(
                    "${workspaces.size} saved app workspace(s)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onRefresh) { Text("Refresh") }
        }
        if (workspaces.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(0.42f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(workspaces, key = { it.packageName }) { workspace ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { selectedWorkspace = workspace }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(workspace.appLabel, fontWeight = FontWeight.Medium)
                                Text(
                                    "${workspace.apkCount} APK(s) • ${String.format(Locale.US, "%.1f", workspace.totalBytes / (1024.0 * 1024.0))} MB",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                if (workspace.filesValid) "Ready" else "Check files",
                                color = if (workspace.filesValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
        Text("Installed apps", fontWeight = FontWeight.SemiBold)
        Text(
            "${apps.size} launchable apps",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (filteredApps.isEmpty()) {
            Text("No matching launchable apps found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(app.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${app.apkPaths.size} APK file(s)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Prepared APKs are stored privately. CloneHub can now rebuild a separate package and hand it to Android's installer.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    selectedApp?.let { app ->
        AlertDialog(
            onDismissRequest = { if (preparingPackage != app.packageName) selectedApp = null },
            title = { Text(app.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Package: ${app.packageName}")
                    Text("APK files found: ${app.apkPaths.size}")
                    app.apkPaths.forEachIndexed { index, path ->
                        Text("${if (index == 0) "Base APK" else "Split APK $index"}: $path", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    preparationResult?.let { Text(it, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                Button(
                    enabled = preparingPackage == null,
                    onClick = {
                        preparingPackage = app.packageName
                        preparationResult = "Copying APK files and verifying checksums..."
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
                TextButton(enabled = preparingPackage != app.packageName, onClick = { selectedApp = null }) {
                    Text("Close")
                }
            }
        )
    }

    selectedWorkspace?.let { workspace ->
        AlertDialog(
            onDismissRequest = { if (analyzingPackage != workspace.packageName && cloningPackage != workspace.packageName) selectedWorkspace = null },
            title = { Text(workspace.appLabel) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Package: ${workspace.packageName}")
                    Text("APK files: ${workspace.apkCount}")
                    Text("Total size: ${String.format(Locale.US, "%.1f", workspace.totalBytes / (1024.0 * 1024.0))} MB")
                    Text("Integrity status: ${if (workspace.filesValid) "Files present" else "Missing or incomplete files"}")
                    Text("Saved path: ${workspace.directory}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    analysisResult?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    cloneResult?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        enabled = workspace.filesValid && analyzingPackage == null && cloningPackage == null,
                        onClick = {
                            cloningPackage = workspace.packageName
                            cloneResult = "Rewriting package metadata, signing APKs, and preparing Android installation..."
                            onClone(workspace) { result ->
                                cloneResult = result
                                cloningPackage = null
                            }
                        }
                    ) {
                        Text(if (cloningPackage == workspace.packageName) "Building clone..." else "Create & install clone")
                    }
                    TextButton(
                        enabled = workspace.filesValid && analyzingPackage == null && cloningPackage == null,
                        onClick = {
                            analyzingPackage = workspace.packageName
                            analysisResult = "Inspecting package metadata, components, permissions, and signing certificates..."
                            onAnalyze(workspace) { result ->
                                analysisResult = result
                                analyzingPackage = null
                            }
                        }
                    ) {
                        Text(if (analyzingPackage == workspace.packageName) "Analyzing..." else "Analyze APKs")
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = analyzingPackage != workspace.packageName && cloningPackage != workspace.packageName, onClick = { selectedWorkspace = null }) {
                    Text("Close")
                }
            }
        )
    }
}
