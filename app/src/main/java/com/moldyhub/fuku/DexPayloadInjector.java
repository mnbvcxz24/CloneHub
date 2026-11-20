package com.moldyhub.fuku;

import android.content.Context;
import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ByteInputSource;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Injects the clone-owned freeform provider and its HiddenApiBypass dependency. */
final class DexPayloadInjector {
    private static final String RUNTIME_PREFIX = "Lcom/moldyhub/fuku/runtime/";
    private static final String HIDDEN_API_PREFIX = "Lorg/lsposed/hiddenapibypass/";
    private final Context context;

    DexPayloadInjector(Context context) {
        this.context = context.getApplicationContext();
    }

    void inject(File baseApk) throws Exception {
        File sourceApk = new File(context.getApplicationInfo().sourceDir);
        if (!sourceApk.isFile()) {
            throw new IllegalStateException("Fuku's installed APK could not be located for freeform runtime injection.");
        }
        Opcodes opcodes = Opcodes.getDefault();
        Map<String, org.jf.dexlib2.iface.ClassDef> payload = new LinkedHashMap<>();
        try (ZipFile source = new ZipFile(sourceApk)) {
            boolean foundProvider = false;
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().matches("classes(\\d*)\\.dex")) continue;
                try (BufferedInputStream input = new BufferedInputStream(source.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(opcodes, input);
                    for (org.jf.dexlib2.iface.ClassDef cls : dex.getClasses()) {
                        String type = cls.getType();
                        if (type.startsWith(RUNTIME_PREFIX) || type.startsWith(HIDDEN_API_PREFIX)) {
                            payload.put(type, cls);
                            if (type.equals(RUNTIME_PREFIX + "FreeformInitProvider;")) foundProvider = true;
                        }
                    }
                }
            }
            if (!foundProvider) throw new IllegalStateException(
                    "The clone-owned freeform provider was not compiled into Fuku.");
            if (!payload.keySet().stream().anyMatch(type -> type.startsWith(HIDDEN_API_PREFIX))) {
                throw new IllegalStateException("HiddenApiBypass runtime classes are missing from Fuku.");
            }
        }

        Set<String> dexNames = new LinkedHashSet<>();
        Set<String> existingTypes = new HashSet<>();
        try (ZipFile target = new ZipFile(baseApk)) {
            var entries = target.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().matches("classes(\\d*)\\.dex")) continue;
                dexNames.add(entry.getName());
                try (BufferedInputStream input = new BufferedInputStream(target.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(opcodes, input);
                    for (org.jf.dexlib2.iface.ClassDef cls : dex.getClasses()) existingTypes.add(cls.getType());
                }
            }
        }
        if (dexNames.isEmpty()) throw new IllegalStateException("The source APK contains no DEX files.");
        for (String type : payload.keySet()) {
            if (existingTypes.contains(type)) {
                throw new IllegalStateException(
                        "The source app already contains a class required by Fuku's freeform runtime: " + type);
            }
        }

        int highest = 1;
        for (String name : dexNames) {
            String suffix = name.substring("classes".length(), name.length() - ".dex".length());
            highest = Math.max(highest, suffix.isEmpty() ? 1 : Integer.parseInt(suffix));
        }
        String newDexName = "classes" + (highest + 1) + ".dex";
        File outputDex = new File(context.getCacheDir(), "fuku-freeform-payload.dex");
        if (outputDex.exists() && !outputDex.delete()) {
            throw new IllegalStateException("Could not replace the temporary freeform DEX.");
        }
        DexPool.writeTo(outputDex.getAbsolutePath(), new ImmutableDexFile(opcodes, payload.values()));
        if (!outputDex.isFile() || outputDex.length() == 0L) {
            throw new IllegalStateException("The clone freeform DEX could not be generated.");
        }

        ApkModule module = ApkModule.loadApkFile(baseApk);
        module.add(new ByteInputSource(java.nio.file.Files.readAllBytes(outputDex.toPath()), newDexName));
        File temporary = new File(baseApk.getParentFile(), baseApk.getName() + ".freeform.tmp");
        try {
            module.writeApk(temporary);
            if (baseApk.exists() && !baseApk.delete()) {
                throw new IllegalStateException("Could not replace the base APK after freeform injection.");
            }
            if (!temporary.renameTo(baseApk)) {
                java.nio.file.Files.copy(temporary.toPath(), baseApk.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                temporary.delete();
            }
        } finally {
            if (temporary.exists()) temporary.delete();
            if (outputDex.exists()) outputDex.delete();
        }
    }
}