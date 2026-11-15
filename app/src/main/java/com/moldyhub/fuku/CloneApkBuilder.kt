package com.moldyhub.fuku

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
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
import java.util.Properties

data class CloneBuildOutcome(val packageName: String, val apkDirectory: String, val sessionId: Int)

class CloneApkBuilder(private val context: Context) {
    companion object {
        private const val KEYSTORE_PASSWORD = "fuku-local-signing-key"
        private const val INSTALL_ACTION = "com.moldyhub.fuku.CLONE_INSTALL_STATUS"
        private const val MAX_CLONE_INDEX = 999
    }

    fun buildAndInstall(workspacePath: String, originalPackage: String, appLabel: String, allowAnyApp: Boolean = false): CloneBuildOutcome {
        if (!allowAnyApp) require(originalPackage == "com.roblox.client") { "Fuku Lite currently supports only the installed Roblox app." }
        require(originalPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            "This package name cannot be safely converted to a clone ID."
        }
        require(originalPackage.length <= 220) { "The source package name is too long to create a separate package ID." }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            error("Allow Fuku to install unknown apps in Android Settings, then try again.")
        }

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

        val cloneIndex = (1..MAX_CLONE_INDEX).firstOrNull { index ->
            val candidate = "$originalPackage.fuku.c${index.toString().padStart(3, '0')}"
            try {
                context.packageManager.getPackageInfo(candidate, 0)
                false
            } catch (_: PackageManager.NameNotFoundException) {
                true
            }
        } ?: error("Clone limit reached for this source app.")
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

        val mutator = ManifestAndArscMutator()
        val mutation = mutator.mutateBaseApk(
            baseApkFile = stagedApks.first(),
            origPkg = originalPackage,
            clonePkg = clonePackage,
            cloneSuffix = suffix,
            customAppLabel = "Fuku " + cloneIndex.toString().padStart(2, '0')
        )
        // Resolve relative Application and AppComponentFactory names back to the original DEX namespace.
        fixApplicationClassNames(stagedApks.first(), mutation.originalApplicationClass, mutation.originalAppComponentFactory)
        require(mutation.mutatedPackage == clonePackage) { "Manifest package mutation did not complete." }

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

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(clonePackage)
            setSize(signedApks.sumOf { it.length() })
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            signedApks.forEach { apk ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
            }
            val callback = Intent(context, CloneInstallReceiver::class.java)
                .setAction(INSTALL_ACTION)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                .putExtra("clone_package", clonePackage)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pendingIntent.intentSender)
        }
        return CloneBuildOutcome(clonePackage, outputDir.absolutePath, sessionId)
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

class CloneInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = intent.getStringExtra("clone_package") ?: "clone"
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "Installed $packageName as a separate app.", Toast.LENGTH_LONG).show()
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, "Clone install failed ($status): $detail", Toast.LENGTH_LONG).show()
            }
        }
    }
}
