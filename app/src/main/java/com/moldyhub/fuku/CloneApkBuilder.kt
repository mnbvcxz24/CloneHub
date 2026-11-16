package com.moldyhub.fuku

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import dev.cloneforge.patcher.manifest.ManifestAndArscMutator
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class CloneBuildOutcome(val packageName: String, val fileName: String, val savedLocation: String)

private val cloneIndexLock = Any()

class CloneApkBuilder(private val context: Context) {
    companion object {
        private const val KEYSTORE_PASSWORD = "fuku-local-signing-key"
        private const val MAX_CLONE_INDEX = 999
        private const val RUNTIME_SETUP_ACTIVITY = "com.fuku.cloneruntime.RuntimeEntryActivity"
        private const val RUNTIME_OVERLAY_SERVICE = "com.fuku.cloneruntime.RuntimeOverlayService"
        private const val RUNTIME_LAUNCHER_METADATA = "com.fuku.cloneruntime.ORIGINAL_LAUNCHER"
    }

    fun build(workspacePath: String, originalPackage: String, appLabel: String, mode: CloneOutputMode): CloneBuildOutcome {
        require(originalPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            "This package name cannot be safely converted to a clone ID."
        }
        require(originalPackage.length <= 220) { "The source package name is too long to create a separate package ID." }

        val workspace = File(workspacePath)
        require(workspace.isDirectory) { "Prepared APK workspace is missing." }
        val metadata = Properties()
        FileInputStream(File(workspace, "workspace.properties")).use { metadata.load(it) }
        require(metadata.getProperty("packageName") == originalPackage) { "Workspace package does not match the selected app." }
        val count = metadata.getProperty("apkCount")?.toIntOrNull() ?: error("Workspace APK count is invalid.")
        require(count > 0) { "No APK files were prepared." }

        val sourceApks = (0 until count).map { index ->
            val name = metadata.getProperty("apk.$index.name") ?: error("APK filename is missing.")
            val file = File(workspace, name)
            require(file.isFile && file.canRead()) { "Prepared APK is missing: $name" }
            val expectedHash = metadata.getProperty("apk.$index.sha256") ?: error("APK checksum is missing.")
            require(sha256(file) == expectedHash) { "APK checksum changed: $name" }
            file
        }
        require(sourceApks.first().name == "base.apk") { "The base APK must be first in the prepared workspace." }
        val baseInfo = context.packageManager.getPackageArchiveInfo(sourceApks.first().absolutePath, 0)
            ?: error("Android cannot read the prepared base APK.")
        require(baseInfo.packageName == originalPackage) { "The prepared base APK package ID does not match the workspace." }

        val cloneIndex = synchronized(cloneIndexLock) {
            val sequenceFile = File(context.filesDir, "clone-indexes.properties")
            val sequences = Properties()
            if (sequenceFile.isFile) FileInputStream(sequenceFile).use { sequences.load(it) }
            var next = (sequences.getProperty(originalPackage)?.toIntOrNull() ?: 0) + 1
            while (next <= MAX_CLONE_INDEX) {
                val candidatePackage = "$originalPackage.fuku.c${next.toString().padStart(3, '0')}"
                val installed = try {
                    context.packageManager.getPackageInfo(candidatePackage, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
                if (!installed) break
                next++
            }
            require(next <= MAX_CLONE_INDEX) { "Clone limit reached for this source app." }
            sequences.setProperty(originalPackage, next.toString())
            val temporary = File(sequenceFile.parentFile, sequenceFile.name + ".tmp")
            FileOutputStream(temporary).use { sequences.store(it, "Fuku generated clone indexes") }
            if (sequenceFile.exists() && !sequenceFile.delete()) error("Could not update clone sequence.")
            if (!temporary.renameTo(sequenceFile)) {
                temporary.copyTo(sequenceFile, overwrite = true)
                temporary.delete()
            }
            next
        }
        val clonePackage = "$originalPackage.fuku.c${cloneIndex.toString().padStart(3, '0')}"
        val suffix = "fuku_c${cloneIndex.toString().padStart(3, '0')}"

        val staging = File(context.cacheDir, "clone_build/$clonePackage").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val stagedApks = sourceApks.map { source ->
            val copy = File(staging, source.name)
            source.copyTo(copy, overwrite = true)
            copy
        }

        val originalLauncherClass = readMainActivityClass(stagedApks.first(), originalPackage)

        val mutator = ManifestAndArscMutator()
        val mutation = mutator.mutateBaseApk(
            baseApkFile = stagedApks.first(),
            origPkg = originalPackage,
            clonePkg = clonePackage,
            cloneSuffix = suffix,
            customAppLabel = "Fuku " + cloneIndex.toString().padStart(3, '0'),
            forceLandscape = originalPackage == "com.roblox.client"
        )
        // Resolve relative Application and AppComponentFactory names back to the original DEX namespace.
        fixApplicationClassNames(stagedApks.first(), mutation.originalApplicationClass, mutation.originalAppComponentFactory)
        require(mutation.mutatedPackage == clonePackage) { "Manifest package mutation did not complete." }
        injectCloneRuntime(stagedApks.first(), originalLauncherClass)

        val authorities = mutation.authorityMap.toMutableMap()
        val permissions = mutation.permissionMap.toMutableMap()
        val accounts = mutation.accountTypeMap.toMutableMap()
        stagedApks.drop(1).forEach { split ->
            mutator.mutateSplitApk(
                splitApkFile = split,
                origPkg = originalPackage,
                clonePkg = clonePackage,
                cloneSuffix = suffix,
                authorityMap = authorities,
                permissionMap = permissions,
                accountTypeMap = accounts
            )
        }

        val signer = loadOrCreateSigner()
        val outputDir = File(context.filesDir, "clones/$clonePackage").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val signedApks = stagedApks.map { input ->
            val output = File(outputDir, input.name)
            signApk(input, output, signer.first, signer.second)
            output
        }
        require(signedApks.all { it.isFile && it.length() > 0L }) { "One or more signed APKs are empty." }

        val singleApk = signedApks.size == 1
        val number = cloneIndex.toString().padStart(3, '0')
        val extension = if (singleApk) ".apk" else ".zip"
        val cloneName = "Fuku $number"
        val fileName = "$cloneName$extension"
        val savedLocation = if (mode == CloneOutputMode.INSTALL) {
            installApks(clonePackage, signedApks)
            "Android package installer"
        } else {
            saveExport(fileName, singleApk, signedApks)
        }
        outputDir.deleteRecursively()
        staging.deleteRecursively()
        return CloneBuildOutcome(clonePackage, cloneName, savedLocation)
    }

    private fun installApks(clonePackage: String, signedApks: List<File>) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(clonePackage)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                signedApks.forEach { apk ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        apk.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, CloneInstallReceiver::class.java).putExtra("packageName", clonePackage)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private fun saveExport(fileName: String, singleApk: Boolean, signedApks: List<File>): String {
        val mimeType = if (singleApk) "application/vnd.android.package-archive" else "application/zip"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Fuku")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create export in Downloads/Fuku.")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { writeExport(it, singleApk, signedApks) }
                    ?: error("Could not open exported file.")
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                return "Downloads/Fuku/$fileName"
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Fuku")
        if (!folder.exists() && !folder.mkdirs()) error("Could not create Fuku export folder.")
        val outputFile = File(folder, fileName)
        FileOutputStream(outputFile).use { writeExport(it, singleApk, signedApks) }
        return outputFile.absolutePath
    }

    private fun writeExport(output: java.io.OutputStream, singleApk: Boolean, signedApks: List<File>) {
        if (singleApk) {
            signedApks.single().inputStream().use { it.copyTo(output, 64 * 1024) }
        } else {
            ZipOutputStream(output).use { zip ->
                signedApks.forEach { apk ->
                    zip.putNextEntry(ZipEntry(apk.name))
                    apk.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun readMainActivityClass(apkFile: File, packageName: String): String {
        val module = ApkModule.loadApkFile(apkFile)
        module.setLoadDefaultFramework(false)
        val manifest = module.androidManifest ?: error("Source APK manifest is missing.")
        val mainActivity = manifest.getMainActivity() ?: error("Source APK has no MAIN activity.")
        val rawName = mainActivity.searchAttributeByResourceId(0x01010003)?.getValueString()
            ?.takeIf { it.isNotBlank() } ?: error("Could not identify the source app launcher activity.")
        return when {
            rawName.startsWith(".") -> packageName + rawName
            '.' !in rawName -> packageName + "." + rawName
            else -> rawName
        }
    }

    private fun injectCloneRuntime(apkFile: File, originalLauncherClass: String) {
        val module = ApkModule.loadApkFile(apkFile)
        module.setLoadDefaultFramework(false)
        val manifest = module.androidManifest ?: error("Mutated clone manifest is missing.")
        val mainActivity = manifest.getMainActivity()
            ?: error("Could not find the original launcher activity after package mutation.")
        val filters = mainActivity.getElements("intent-filter")
        while (filters.hasNext()) {
            val filter = filters.next()
            val categories = filter.getElements("category")
            while (categories.hasNext()) {
                val category = categories.next()
                val name = category.searchAttributeByResourceId(0x01010003)?.getValueString()
                if (name == "android.intent.category.LAUNCHER") category.removeSelf()
            }
        }

        manifest.addUsesPermission("android.permission.SYSTEM_ALERT_WINDOW")
        manifest.addUsesPermission("android.permission.FOREGROUND_SERVICE")
        manifest.addUsesPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")

        val application = manifest.getOrCreateApplicationElement()
        val setupActivity = manifest.getOrCreateActivity(RUNTIME_SETUP_ACTIVITY, false)
        setupActivity.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true)
        setupActivity.getOrCreateAndroidAttribute("theme", 0x01010000)
            .setValueAsString("@android:style/Theme.Material.Light.NoActionBar")
        setupActivity.getOrCreateAndroidAttribute("label", 0x01010001).setValueAsString("Fuku " + clonePackage.substringAfterLast(".c").padStart(3, '0'))
        val launcherFilter = setupActivity.newElement("intent-filter")
        launcherFilter.newElement("action").getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("android.intent.action.MAIN")
        launcherFilter.newElement("category").getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("android.intent.category.LAUNCHER")

        val service = application.newElement("service")
        service.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(RUNTIME_OVERLAY_SERVICE)
        service.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(false)
        service.getOrCreateAndroidAttribute("foregroundServiceType", 0x010105A9)
            .setValueAsString("specialUse")
        val subtype = service.newElement("property")
        subtype.getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
        subtype.getOrCreateAndroidAttribute("value", 0x01010024)
            .setValueAsString("Displays floating controls for this cloned app")

        val launcherMetadata = application.newElement("meta-data")
        launcherMetadata.getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString(RUNTIME_LAUNCHER_METADATA)
        launcherMetadata.getOrCreateAndroidAttribute("value", 0x01010024)
            .setValueAsString(originalLauncherClass)

        val rewritten = File(apkFile.parentFile, apkFile.name + ".runtime.tmp")
        module.writeApk(rewritten)
        if (apkFile.exists() && !apkFile.delete()) error("Could not replace the clone manifest.")
        if (!rewritten.renameTo(apkFile)) {
            rewritten.copyTo(apkFile, overwrite = true)
            rewritten.delete()
        }
        appendRuntimeDex(apkFile)
    }

    private fun appendRuntimeDex(apkFile: File) {
        val runtimeDex = context.assets.open("clone-runtime.dex").use { it.readBytes() }
        require(runtimeDex.isNotEmpty()) { "Embedded clone runtime DEX is empty." }
        val temporary = File(apkFile.parentFile, apkFile.name + ".dex.tmp")
        ZipFile(apkFile).use { source ->
            val dexNumbers = source.entries().asSequence().mapNotNull { entry ->
                val match = Regex("^classes(\\d*)\\.dex$").matchEntire(entry.name) ?: return@mapNotNull null
                match.groupValues[1].ifEmpty { "1" }.toIntOrNull()
            }.toList()
            require(dexNumbers.isNotEmpty()) { "Source APK has no DEX bytecode." }
            val nextDexNumber = (dexNumbers.maxOrNull() ?: 1) + 1
            val nextDex = "classes" + nextDexNumber + ".dex"
            ZipOutputStream(FileOutputStream(temporary)).use { output ->
                val entries = source.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val copy = ZipEntry(entry.name).apply {
                        time = entry.time
                        comment = entry.comment
                        if (entry.method == ZipEntry.STORED) {
                            method = ZipEntry.STORED
                            size = entry.size
                            compressedSize = entry.size
                            crc = entry.crc
                        } else {
                            method = ZipEntry.DEFLATED
                        }
                    }
                    output.putNextEntry(copy)
                    source.getInputStream(entry).use { input -> input.copyTo(output) }
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry(nextDex))
                output.write(runtimeDex)
                output.closeEntry()
            }
        }
        if (apkFile.exists() && !apkFile.delete()) error("Could not replace the APK after adding runtime DEX.")
        if (!temporary.renameTo(apkFile)) {
            temporary.copyTo(apkFile, overwrite = true)
            temporary.delete()
        }
    }

    private fun fixApplicationClassNames(apkFile: File, applicationClass: String?, factoryClass: String?) {
        if (applicationClass == null && factoryClass == null) return
        val module = ApkModule.loadApkFile(apkFile)
        module.setLoadDefaultFramework(false)
        val manifest = module.androidManifest ?: error("Mutated APK manifest is missing.")
        val appElement = manifest.getManifestElement()?.getElements("application")?.let { iterator ->
            if (iterator.hasNext()) iterator.next() else null
        } ?: error("Mutated APK application element is missing.")
        applicationClass?.let {
            appElement.searchAttributeByResourceId(0x01010003)?.setValueAsString(it)
        }
        factoryClass?.let {
            appElement.searchAttributeByResourceId(0x0101057a)?.setValueAsString(it)
        }
        val temporary = File(apkFile.parentFile, apkFile.name + ".appclass.tmp")
        module.writeApk(temporary)
        if (apkFile.exists() && !apkFile.delete()) error("Could not replace the rewritten APK.")
        if (!temporary.renameTo(apkFile)) {
            temporary.copyTo(apkFile, overwrite = true)
            temporary.delete()
        }
    }

    private fun signApk(input: File, output: File, key: PrivateKey, certificate: X509Certificate) {
        val config = ApkSigner.SignerConfig.Builder("Fuku", key, listOf(certificate)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign()
    }

    private fun loadOrCreateSigner(): Pair<PrivateKey, X509Certificate> {
        val keyFile = File(context.filesDir, "signing/fuku-keystore.p12")
        keyFile.parentFile?.mkdirs()
        val password = KEYSTORE_PASSWORD.toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        if (keyFile.isFile) {
            FileInputStream(keyFile).use { store.load(it, password) }
        } else {
            store.load(null, password)
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=Fuku Local APK Signer")
            val certificateBuilder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                Date(now - 60_000L),
                Date(now + 10L * 365 * 24 * 60 * 60 * 1000),
                subject,
                pair.public
            )
            val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
            val certificate = JcaX509CertificateConverter().getCertificate(certificateBuilder.build(contentSigner))
            certificate.verify(pair.public)
            store.setKeyEntry("fuku", pair.private, password, arrayOf(certificate))
            FileOutputStream(keyFile).use { store.store(it, password) }
        }
        val privateKey = store.getKey("fuku", password) as? PrivateKey
            ?: error("Fuku signing key is unavailable.")
        val certificate = store.getCertificate("fuku") as? X509Certificate
            ?: error("Fuku signing certificate is unavailable.")
        return privateKey to certificate
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

