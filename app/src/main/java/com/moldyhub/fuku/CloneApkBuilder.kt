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
import android.util.Log
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CloneBuildOutcome(val packageName: String, val fileName: String, val savedLocation: String)

private val cloneIndexLock = Any()

class CloneApkBuilder(private val context: Context) {
    companion object {
        private const val KEYSTORE_PASSWORD = "fuku-local-signing-key"
        private const val MAX_CLONE_INDEX = 999
        private const val LOG_TAG = "FukuClone"
        private const val NATIVE_LIBRARY_ALIGNMENT = 16_384
    }


    private inline fun <T> buildStep(name: String, action: () -> T): T {
        Log.d(LOG_TAG, "START: $name")
        return try {
            action()
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "FAILED: $name: ${error.message}", error)
            throw error
        }
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
        val mutation = buildStep("Mutate base APK manifest and resources") { mutator.mutateBaseApk(
            baseApkFile = stagedApks.first(),
            origPkg = originalPackage,
            clonePkg = clonePackage,
            cloneSuffix = suffix,
            customAppLabel = "$appLabel (Fuku)",
            forceLandscape = false
        ) }
        // Resolve relative Application and AppComponentFactory names back to the original DEX namespace.
        buildStep("Preserve original Application and AppComponentFactory names") { fixApplicationClassNames(stagedApks.first(), mutation.originalApplicationClass, mutation.originalAppComponentFactory) }
        require(mutation.mutatedPackage == clonePackage) { "Manifest package mutation did not complete." }
        val originalLauncherActivity = mutation.originalLauncherActivity
            ?: error("The source app has no supported launcher activity for automatic freeform launch.")
        buildStep("Inject freeform launcher DEX payload") { DexPayloadInjector(context).inject(stagedApks.first(), originalLauncherActivity) }

        val authorities = mutation.authorityMap.toMutableMap()
        val permissions = mutation.permissionMap.toMutableMap()
        val accounts = mutation.accountTypeMap.toMutableMap()
        stagedApks.drop(1).forEach { split ->
            buildStep("Mutate split APK ${split.name}") { mutator.mutateSplitApk(
                splitApkFile = split,
                origPkg = originalPackage,
                clonePkg = clonePackage,
                cloneSuffix = suffix,
                authorityMap = authorities,
                permissionMap = permissions,
                accountTypeMap = accounts
            ) }
        }

        // Normalize and validate each final signed APK. Signing can change ZIP layout,
        // so test each padding variant against the artifact that will actually be installed.
        val signer = buildStep("Load or create signing identity") { loadOrCreateSigner() }
        val outputDir = File(context.filesDir, "clones/$clonePackage").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val signedApks = stagedApks.mapIndexed { index, input ->
            val output = File(outputDir, input.name)
            buildStep("Normalize, sign, and verify ${input.name}") { normalizeSignAndVerifyApk(
                input,
                output,
                signer.first,
                signer.second
            ) }
            output
        }
        require(signedApks.all { it.isFile && it.length() > 0L }) { "One or more signed APKs are empty." }

        val singleApk = signedApks.size == 1
        val number = cloneIndex.toString().padStart(3, '0')
        val extension = if (singleApk) ".apk" else ".zip"
        val cloneName = "$appLabel - Fuku $number"
        val fileName = "$cloneName$extension"
        val savedLocation = if (mode == CloneOutputMode.INSTALL) {
            buildStep("Install signed clone APK set") { installApks(clonePackage, signedApks) }
            "Android package installer"
        } else {
            buildStep("Export signed clone APK set") { saveExport(fileName, singleApk, signedApks) }
        }
        outputDir.deleteRecursively()
        staging.deleteRecursively()
        return CloneBuildOutcome(clonePackage, cloneName, savedLocation)
    }

    /**
     * Rewrites, signs, and validates each padding variant. The signed APK is
     * authoritative: resources.arsc and every STORED native library are checked
     * against their required data offsets after signing.
     */
    private fun normalizeSignAndVerifyApk(
        apkFile: File,
        outputFile: File,
        key: PrivateKey,
        certificate: X509Certificate
    ) {
        Log.d(LOG_TAG, "Normalizing APK ${apkFile.name}; resources.arsc retry loop active")
        var lastAlignmentError: IllegalArgumentException? = null
        for (nudge in 0..3) {
            buildStep("Rewrite ZIP alignment for ${apkFile.name} (attempt ${nudge + 1}/4)") { rewriteApkForAndroidInstall(apkFile, nudge) }

            val normalizedInfo = resourcesArscInfo(apkFile)
            if (normalizedInfo != null) {
                require(normalizedInfo.first == ZipEntry.STORED) {
                    "APK normalization failed: resources.arsc is still compressed."
                }
            }

            if (outputFile.exists() && !outputFile.delete()) {
                error("Could not replace the previous signed APK candidate.")
            }
            buildStep("Sign aligned APK ${apkFile.name}") { signApk(apkFile, outputFile, key, certificate) }
            try {
                verifyResourcesArscAlignment(outputFile)
                verifyStoredNativeLibrariesAlignment(outputFile)
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
                        val method = if (source.name == "resources.arsc") ZipEntry.STORED else source.method
                        require(method == ZipEntry.STORED || method == ZipEntry.DEFLATED) {
                            "Unsupported ZIP compression method for ${source.name}: $method"
                        }
                        val cleanExtra = stripAlignmentExtra(source.extra)
                        val target = ZipEntry(source.name).apply {
                            time = source.time
                            source.comment?.let { comment = it }
                            this.method = method
                            extra = when {
                                source.name == "resources.arsc" ->
                                    alignedExtra(counted.count, source.name, 4, alignmentNudge, cleanExtra.size)
                                method == ZipEntry.STORED && source.name.endsWith(".so", ignoreCase = true) ->
                                    alignedExtra(counted.count, source.name, NATIVE_LIBRARY_ALIGNMENT, 0, cleanExtra.size)
                                else -> cleanExtra
                            }
                            if (method == ZipEntry.STORED) {
                                size = source.size
                                compressedSize = source.size
                                crc = source.crc
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
            verifyStoredNativeLibrariesAlignment(apkFile)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Removes stale ZIP alignment extra fields (0xD935), preserving other fields. */
    private fun stripAlignmentExtra(extra: ByteArray?): ByteArray {
        if (extra == null || extra.isEmpty()) return ByteArray(0)
        val output = java.io.ByteArrayOutputStream(extra.size)
        var offset = 0
        while (offset < extra.size) {
            require(offset + 4 <= extra.size) {
                "Malformed ZIP extra field: truncated field header at byte $offset."
            }
            val id = (extra[offset].toInt() and 0xff) or
                ((extra[offset + 1].toInt() and 0xff) shl 8)
            val size = (extra[offset + 2].toInt() and 0xff) or
                ((extra[offset + 3].toInt() and 0xff) shl 8)
            val end = offset + 4 + size
            require(end <= extra.size) {
                "Malformed ZIP extra field 0x${id.toString(16)}: declared size exceeds entry extra data."
            }
            if (id != 0xD935) output.write(extra, offset, end - offset)
            offset = end
        }
        return output.toByteArray()
    }

    /** Creates ZIP extra field 0xD935 so payload begins on the requested boundary. */
    private fun alignedExtra(
        currentOffset: Long,
        entryName: String,
        alignment: Int,
        alignmentNudge: Int,
        preservedExtraLength: Int
    ): ByteArray {
        require(alignment > 0 && (alignment and (alignment - 1)) == 0) {
            "ZIP alignment must be a positive power of two."
        }
        val nameLength = entryName.toByteArray(Charsets.UTF_8).size
        require(preservedExtraLength >= 0) { "ZIP preserved extra-field length cannot be negative." }
        // The local header contains the filename, preserved source extra fields,
        // and our new 0xD935 field header before the payload. Include every byte
        // when calculating padding; otherwise timestamps/other extras shift data.
        val basePadding = ((alignment - ((currentOffset + 30L + nameLength +
            preservedExtraLength + 4L) % alignment)) % alignment).toInt()
        val paddingLength = (basePadding + alignmentNudge) % alignment
        return byteArrayOf(0x35, 0xD9.toByte(), paddingLength.toByte(), (paddingLength ushr 8).toByte()) +
            ByteArray(paddingLength)
    }

    /** Verifies actual local-header offsets for every STORED .so entry. */
    private fun verifyStoredNativeLibrariesAlignment(apkFile: File) {
        val misaligned = storedNativeLibraryOffsets(apkFile)
            .filter { (_, offset) -> offset % NATIVE_LIBRARY_ALIGNMENT != 0L }
        require(misaligned.isEmpty()) {
            val details = misaligned.take(5).joinToString { (name, offset) -> "$name at $offset" }
            "APK native-library alignment failed: STORED .so payloads must be 16 KB aligned in ${apkFile.name}. Misaligned: $details"
        }
    }

    /** Returns (entry name, data offset) for each STORED .so in the ZIP central directory. */
    private fun storedNativeLibraryOffsets(apkFile: File): List<Pair<String, Long>> {
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
            require(entryCount != 0xffff && centralOffset != 0xffffffffL) {
                "ZIP64 APKs are not supported by native-library alignment verification: ${apkFile.name}."
            }
            val result = ArrayList<Pair<String, Long>>()
            var position = centralOffset
            repeat(entryCount) {
                file.seek(position)
                require(readLeInt(file) == 0x02014b50L) {
                    "APK central directory is invalid: ${apkFile.name}."
                }
                file.seek(position + 10L)
                val method = readLeShort(file)
                file.seek(position + 28L)
                val nameLength = readLeShort(file)
                val extraLength = readLeShort(file)
                val commentLength = readLeShort(file)
                file.seek(position + 42L)
                val localHeaderOffset = readLeInt(file)
                val nameBytes = ByteArray(nameLength)
                file.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                if (method == ZipEntry.STORED && name.endsWith(".so", ignoreCase = true)) {
                    file.seek(localHeaderOffset)
                    require(readLeInt(file) == 0x04034b50L) {
                        "Native library local header is invalid for $name."
                    }
                    file.seek(localHeaderOffset + 26L)
                    val localNameLength = readLeShort(file)
                    val localExtraLength = readLeShort(file)
                    result.add(name to (localHeaderOffset + 30L + localNameLength + localExtraLength))
                }
                position += 46L + nameLength + extraLength + commentLength
            }
            return result
        }
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
                // PackageInstaller sends its status callback with background
                // activity launches explicitly disallowed. Receive the result in
                // a BroadcastReceiver instead; while Fuku is visibly open, the
                // receiver can hand Android's confirmation intent to the user.
                val callback = Intent(context, CloneInstallReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra("packageName", clonePackage)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
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

