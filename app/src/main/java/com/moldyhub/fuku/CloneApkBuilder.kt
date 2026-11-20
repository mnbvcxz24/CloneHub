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
import java.util.zip.ZipOutputStream

data class CloneBuildOutcome(val packageName: String, val fileName: String, val savedLocation: String)

private val cloneIndexLock = Any()

class CloneApkBuilder(private val context: Context) {
    companion object {
        private const val KEYSTORE_PASSWORD = "fuku-local-signing-key"
        private const val MAX_CLONE_INDEX = 999
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

        // Keep one stable package ID per source app. Rebuilding the same source
        // updates its existing clone instead of installing c002, c003, etc.
        val cloneIndex = synchronized(cloneIndexLock) {
            val sequenceFile = File(context.filesDir, "clone-indexes.properties")
            val sequences = Properties()
            if (sequenceFile.isFile) FileInputStream(sequenceFile).use { sequences.load(it) }
            val savedIndex = sequences.getProperty(originalPackage)?.toIntOrNull()
            val existingIndex = if (savedIndex == null || savedIndex !in 1..MAX_CLONE_INDEX) {
                (1..MAX_CLONE_INDEX).firstOrNull { index ->
                    val candidatePackage = "$originalPackage.fuku.c${index.toString().padStart(3, '0')}"
                    try {
                        context.packageManager.getPackageInfo(candidatePackage, 0)
                        true
                    } catch (_: PackageManager.NameNotFoundException) {
                        false
                    }
                }
            } else null
            val stableIndex = savedIndex?.takeIf { it in 1..MAX_CLONE_INDEX } ?: existingIndex ?: 1
            sequences.setProperty(originalPackage, stableIndex.toString())
            val temporary = File(sequenceFile.parentFile, sequenceFile.name + ".tmp")
            FileOutputStream(temporary).use { sequences.store(it, "Fuku stable clone indexes") }
            if (sequenceFile.exists() && !sequenceFile.delete()) error("Could not update clone sequence.")
            if (!temporary.renameTo(sequenceFile)) {
                temporary.copyTo(sequenceFile, overwrite = true)
                temporary.delete()
            }
            stableIndex
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

        val mutator = ManifestAndArscMutator()
        val mutation = mutator.mutateBaseApk(
            baseApkFile = stagedApks.first(),
            origPkg = originalPackage,
            clonePkg = clonePackage,
            cloneSuffix = suffix,
            customAppLabel = "$appLabel (Fuku)",
            forceLandscape = false
        )
        // Resolve relative Application and AppComponentFactory names back to the original DEX namespace.
        fixApplicationClassNames(stagedApks.first(), mutation.originalApplicationClass, mutation.originalAppComponentFactory)
        require(mutation.mutatedPackage == clonePackage) { "Manifest package mutation did not complete." }
        val originalLauncherActivity = mutation.originalLauncherActivity
            ?: error("The source app has no supported launcher activity for automatic freeform launch.")
        DexPayloadInjector(context).inject(stagedApks.first(), originalLauncherActivity)

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

        // Normalize and validate each final signed APK. Signing can change ZIP layout,
        // so test each padding variant against the artifact that will actually be installed.
        val signer = loadOrCreateSigner()
        val outputDir = File(context.filesDir, "clones/$clonePackage").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val signedApks = stagedApks.mapIndexed { index, input ->
            val output = File(outputDir, input.name)
            normalizeSignAndVerifyApk(
                input,
                output,
                signer.first,
                signer.second
            )
            output
        }
        require(signedApks.all { it.isFile && it.length() > 0L }) { "One or more signed APKs are empty." }

        val singleApk = signedApks.size == 1
        val number = cloneIndex.toString().padStart(3, '0')
        val extension = if (singleApk) ".apk" else ".zip"
        val cloneName = "$appLabel - Fuku $number"
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

    /**
     * Rewrites, signs, and validates each padding variant. The signed APK is
     * authoritative: the variant is accepted only when its resources.arsc
     * payload is STORED and starts at a 4-byte boundary after signing.
     */
    private fun normalizeSignAndVerifyApk(
        apkFile: File,
        outputFile: File,
        key: PrivateKey,
        certificate: X509Certificate
    ) {
        var lastAlignmentError: IllegalArgumentException? = null
        for (nudge in 0..3) {
            rewriteApkForAndroidInstall(apkFile, nudge)

            val normalizedInfo = resourcesArscInfo(apkFile)
            if (normalizedInfo != null) {
                require(normalizedInfo.first == ZipEntry.STORED) {
                    "APK normalization failed: resources.arsc is still compressed."
                }
            }

            if (outputFile.exists() && !outputFile.delete()) {
                error("Could not replace the previous signed APK candidate.")
            }
            signApk(apkFile, outputFile, key, certificate)
            try {
                verifyResourcesArscAlignment(outputFile)
                return
            } catch (error: IllegalArgumentException) {
                if (!error.message.orEmpty().contains("resources.arsc is misaligned")) throw error
                lastAlignmentError = error
            }
        }
        throw lastAlignmentError
            ?: IllegalStateException("APK normalization failed: signed resources.arsc could not be aligned.")
    }

    private fun verifyResourcesArscAlignment(apkFile: File) {
        val info = resourcesArscInfo(apkFile) ?: return
        require(info.first == ZipEntry.STORED) {
            "Signed APK validation failed: resources.arsc is compressed in ${apkFile.name}."
        }
        require(info.second % 4L == 0L) {
            "Signed APK validation failed: resources.arsc is misaligned in ${apkFile.name} (offset ${info.second})."
        }
    }

    /** Returns the compression method and actual data offset from ZIP headers. */
    private fun resourcesArscInfo(apkFile: File): Pair<Int, Long>? {
        java.io.RandomAccessFile(apkFile, "r").use { file ->
            val length = file.length()
            val scanStart = (length - 65_557L).coerceAtLeast(0L)
            var eocd = -1L
            var cursor = length - 22L
            while (cursor >= scanStart) {
                file.seek(cursor)
                if (readLeInt(file) == 0x06054b50L) { eocd = cursor; break }
                cursor--
            }
            require(eocd >= 0L) { "APK ZIP end record is missing: ${apkFile.name}." }
            file.seek(eocd + 10L)
            val entryCount = readLeShort(file)
            file.seek(eocd + 16L)
            val centralOffset = readLeInt(file)
            var position = centralOffset
            repeat(entryCount) {
                file.seek(position)
                require(readLeInt(file) == 0x02014b50L) { "APK central directory is invalid: ${apkFile.name}." }
                file.seek(position + 10L)
                val method = readLeShort(file)
                file.seek(position + 28L)
                val nameLength = readLeShort(file)
                val extraLength = readLeShort(file)
                val commentLength = readLeShort(file)
                file.seek(position + 42L)
                val localHeaderOffset = readLeInt(file)
                val name = ByteArray(nameLength)
                file.readFully(name)
                if (String(name, Charsets.UTF_8) == "resources.arsc") {
                    file.seek(localHeaderOffset)
                    require(readLeInt(file) == 0x04034b50L) { "resources.arsc local header is invalid." }
                    file.seek(localHeaderOffset + 26L)
                    val localNameLength = readLeShort(file)
                    val localExtraLength = readLeShort(file)
                    return method to (localHeaderOffset + 30L + localNameLength + localExtraLength)
                }
                position += 46L + nameLength + extraLength + commentLength
            }
            return null
        }
    }

    private fun readLeShort(file: java.io.RandomAccessFile): Int {
        val a = file.read(); val b = file.read()
        require(a >= 0 && b >= 0) { "Unexpected end of APK ZIP data." }
        return a or (b shl 8)
    }

    private fun readLeInt(file: java.io.RandomAccessFile): Long {
        val a = readLeShort(file).toLong()
        val b = readLeShort(file).toLong()
        return a or (b shl 16)
    }

    private fun rewriteApkForAndroidInstall(apkFile: File, alignmentNudge: Int) {
        val temporary = File(apkFile.parentFile, apkFile.name + ".aligned.tmp")
        try {
            java.util.zip.ZipFile(apkFile).use { input ->
                val counted = CountingOutputStream(FileOutputStream(temporary))
                java.util.zip.ZipOutputStream(counted).use { output ->
                    val entries = input.entries()
                    val buffer = ByteArray(64 * 1024)
                    while (entries.hasMoreElements()) {
                        val source = entries.nextElement()
                        val forceStored = source.name == "resources.arsc"
                        val method = if (forceStored) ZipEntry.STORED else source.method
                        require(method == ZipEntry.STORED || method == ZipEntry.DEFLATED) {
                            "Unsupported ZIP compression method for ${source.name}: $method"
                        }
                        val target = ZipEntry(source.name).apply {
                            time = source.time
                            source.comment?.let { comment = it }
                            this.method = method
                            if (method == ZipEntry.STORED) {
                                size = source.size
                                compressedSize = source.size
                                crc = source.crc
                                extra = if (source.name == "resources.arsc") {
                                    alignedExtra(counted.count, source.name, alignmentNudge)
                                } else {
                                    source.extra
                                }
                            }
                        }
                        output.putNextEntry(target)
                        input.getInputStream(source).use { stream ->
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                        output.closeEntry()
                    }

                }
            }

            if (!apkFile.delete()) error("Could not replace APK after ZIP alignment.")
            if (!temporary.renameTo(apkFile)) {
                temporary.copyTo(apkFile, overwrite = true)
                temporary.delete()
            }

            val resources = resourcesArscInfo(apkFile)
            if (resources != null) require(resources.first == ZipEntry.STORED) {
                "APK normalization failed: resources.arsc is still compressed."
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /**
     * ZIP extra field 0xD935 is padding only. Its length is selected so the
     * following STORED entry payload starts at a 4-byte boundary.
     */
    private fun alignedExtra(currentOffset: Long, entryName: String, alignmentNudge: Int): ByteArray {
        val nameLength = entryName.toByteArray(Charsets.UTF_8).size
        val basePadding = ((4 - ((currentOffset + 30L + nameLength + 4L) % 4L)) % 4L).toInt()
        val paddingLength = (basePadding + alignmentNudge) % 4
        return byteArrayOf(0x35, 0xD9.toByte(), paddingLength.toByte(), 0x00) + ByteArray(paddingLength)
    }

    private class CountingOutputStream(output: java.io.OutputStream) :
        java.io.FilterOutputStream(output) {
        var count: Long = 0
            private set

        override fun write(value: Int) {
            out.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out.write(bytes, offset, length)
            count += length
        }
    }

    private fun installApks(clonePackage: String, signedApks: List<File>) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(clonePackage)
            // Fuku is an ordinary, non-privileged installer. Explicitly require
            // Android's confirmation UI instead of relying on OEM-specific
            // handling of USER_ACTION_UNSPECIFIED (which can abort with
            // "User rejected permissions" without a clear approval flow).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
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

