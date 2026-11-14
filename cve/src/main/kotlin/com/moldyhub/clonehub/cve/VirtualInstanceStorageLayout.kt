package com.moldyhub.clonehub.cve

import java.io.File

/**
 * CloneHub-owned directory layout for one virtual-app instance.
 *
 * These paths provide host-managed storage separation only. They do not yet
 * redirect guest Android APIs or create an OS-level app sandbox.
 */
data class VirtualInstanceStoragePaths(
    val instanceRoot: File,
    val dataDirectory: File,
    val filesDirectory: File,
    val cacheDirectory: File,
    val codeCacheDirectory: File,
    val databasesDirectory: File,
    val sharedPreferencesDirectory: File,
    val nativeLibrariesDirectory: File,
    val noBackupDirectory: File
)

/**
 * Resolves and prepares distinct host-private storage directories per instance.
 * The instance ID, rather than packageName, is the storage identity.
 */
class VirtualInstanceStorageLayout(rootDirectory: File) {
    private val root = rootDirectory.canonicalFile

    fun pathsFor(instance: VirtualAppInstance): VirtualInstanceStoragePaths {
        val directoryName = instance.configuration.dataDirectoryName
            ?: "instance_${instance.instanceId}"
        require(SAFE_DIRECTORY_NAME.matches(directoryName)) {
            "Instance storage directory name is not a safe path segment"
        }

        val instanceRoot = File(root, directoryName).canonicalFile
        require(instanceRoot.parentFile == root) {
            "Instance storage directory must remain inside the CloneHub storage root"
        }

        val data = File(instanceRoot, "data")
        return VirtualInstanceStoragePaths(
            instanceRoot = instanceRoot,
            dataDirectory = data,
            filesDirectory = File(data, "files"),
            cacheDirectory = File(data, "cache"),
            codeCacheDirectory = File(data, "code_cache"),
            databasesDirectory = File(data, "databases"),
            sharedPreferencesDirectory = File(data, "shared_prefs"),
            nativeLibrariesDirectory = File(data, "lib"),
            noBackupDirectory = File(data, "no_backup")
        )
    }

    fun prepare(instance: VirtualAppInstance): VirtualInstanceStoragePaths {
        if (!root.exists()) {
            check(root.mkdirs() || root.isDirectory) {
                "Could not create CloneHub instance storage root"
            }
        }
        check(root.isDirectory) { "CloneHub instance storage root is not a directory" }

        val paths = pathsFor(instance)
        val directories = listOf(
            paths.instanceRoot,
            paths.dataDirectory,
            paths.filesDirectory,
            paths.cacheDirectory,
            paths.codeCacheDirectory,
            paths.databasesDirectory,
            paths.sharedPreferencesDirectory,
            paths.nativeLibrariesDirectory,
            paths.noBackupDirectory
        )
        directories.forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "Could not create instance storage directory: ${directory.name}"
            }
        }
        return paths
    }

    fun delete(instance: VirtualAppInstance): Boolean {
        val instanceRoot = pathsFor(instance).instanceRoot
        return !instanceRoot.exists() || instanceRoot.deleteRecursively()
    }

    private companion object {
        val SAFE_DIRECTORY_NAME = Regex("[A-Za-z0-9_-]{1,100}")
    }
}
