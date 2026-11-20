package com.moldyhub.fuku

import android.content.Context
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.writer.pool.DexPool
import java.io.BufferedInputStream
import java.io.File
import java.util.LinkedHashMap
import java.util.zip.ZipFile

/**
 * Copies only the self-contained launcher trampoline and HiddenApiBypass runtime
 * classes from Fuku's installed DEX into a new secondary DEX in the clone.
 * The generated clone therefore does not depend on Fuku staying installed.
 */
internal class DexPayloadInjector(private val context: Context) {
    companion object {
        private const val TRAMPOLINE = "Lcom/moldyhub/fuku/runtime/FreeformTrampolineActivity;"
        private const val HIDDEN_API_PREFIX = "Lorg/lsposed/hiddenapibypass/"
        private const val TARGET_ASSET = "assets/fuku_launcher_target.txt"
    }

    fun inject(baseApk: File, originalLauncherActivity: String) {
        require(originalLauncherActivity.isNotBlank()) { "The original launcher activity is missing." }

        val installedFukuApk = File(context.applicationInfo.sourceDir)
        require(installedFukuApk.isFile) { "Fuku's installed APK could not be located for launcher injection." }

        val payloadClasses = LinkedHashMap<String, org.jf.dexlib2.iface.ClassDef>()
        val opcodes = Opcodes.getDefault()
        ZipFile(installedFukuApk).use { sourceZip ->
            val dexEntries = sourceZip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }
                .toList()
            require(dexEntries.isNotEmpty()) { "Fuku's DEX payload is missing." }

            dexEntries.forEach { entry ->
                val dex = sourceZip.getInputStream(entry).use {
                    DexBackedDexFile.fromInputStream(opcodes, BufferedInputStream(it))
                }
                dex.classes.forEach { classDef ->
                    val descriptor = classDef.type
                    if (descriptor == TRAMPOLINE || descriptor.startsWith(HIDDEN_API_PREFIX)) {
                        payloadClasses[descriptor] = classDef
                    }
                }
            }
        }

        require(payloadClasses.containsKey(TRAMPOLINE)) {
            "The freeform launcher trampoline was not compiled into Fuku."
        }
        require(payloadClasses.keys.any { it.startsWith(HIDDEN_API_PREFIX) }) {
            "The HiddenApiBypass runtime was not found in Fuku's DEX."
        }

        val existingDexNames = linkedSetOf<String>()
        val existingClasses = hashSetOf<String>()
        ZipFile(baseApk).use { targetZip ->
            val dexEntries = targetZip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }
                .toList()
            require(dexEntries.isNotEmpty()) { "The source APK contains no DEX files." }
            dexEntries.forEach { entry ->
                existingDexNames.add(entry.name)
                val dex = targetZip.getInputStream(entry).use {
                    DexBackedDexFile.fromInputStream(opcodes, it)
                }
                dex.classes.forEach { existingClasses.add(it.type) }
            }
        }

        val collision = payloadClasses.keys.firstOrNull { it in existingClasses }
        require(collision == null) {
            "The source app already contains a class required by Fuku's launcher runtime: $collision"
        }

        val highestIndex = existingDexNames.maxOf { name ->
            val suffix = name.removePrefix("classes").removeSuffix(".dex")
            suffix.toIntOrNull() ?: 1
        }
        val newDexName = "classes${highestIndex + 1}.dex"
        val outputDex = File(context.cacheDir, "fuku-freeform-payload.dex")
        if (outputDex.exists()) outputDex.delete()
        DexPool.writeTo(
            outputDex.absolutePath,
            ImmutableDexFile(opcodes, payloadClasses.values)
        )
        require(outputDex.isFile && outputDex.length() > 0L) {
            "The freeform launcher DEX could not be generated."
        }

        val module = ApkModule.loadApkFile(baseApk)
        module.add(ByteInputSource(outputDex.readBytes(), newDexName))
        module.add(
            ByteInputSource(
                (originalLauncherActivity + "\n").toByteArray(Charsets.UTF_8),
                TARGET_ASSET
            )
        )
        val temporary = File(baseApk.parentFile, baseApk.name + ".payload.tmp")
        try {
            module.writeApk(temporary)
            if (baseApk.exists() && !baseApk.delete()) {
                error("Could not replace the base APK after adding the launcher runtime.")
            }
            if (!temporary.renameTo(baseApk)) {
                temporary.copyTo(baseApk, overwrite = true)
                temporary.delete()
            }
        } finally {
            if (temporary.exists()) temporary.delete()
            outputDex.delete()
        }
    }
}
