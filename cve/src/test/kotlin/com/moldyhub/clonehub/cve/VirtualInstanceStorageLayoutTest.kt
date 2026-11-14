package com.moldyhub.clonehub.cve

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualInstanceStorageLayoutTest {
    @Test
    fun samePackageInstancesReceiveIndependentStorageTrees() {
        val root = Files.createTempDirectory("clonehub-instance-layout").toFile()
        try {
            val layout = VirtualInstanceStorageLayout(root)
            val first = VirtualAppInstance.create(
                "com.example.demo",
                VirtualAppConfiguration(dataDirectoryName = "instance_first")
            )
            val second = VirtualAppInstance.create(
                "com.example.demo",
                VirtualAppConfiguration(dataDirectoryName = "instance_second")
            )

            val firstPaths = layout.prepare(first)
            val secondPaths = layout.prepare(second)

            assertNotEquals(firstPaths.instanceRoot.canonicalPath, secondPaths.instanceRoot.canonicalPath)
            assertNotEquals(firstPaths.filesDirectory.canonicalPath, secondPaths.filesDirectory.canonicalPath)
            assertTrue(firstPaths.filesDirectory.isDirectory)
            assertTrue(secondPaths.filesDirectory.isDirectory)
            assertTrue(firstPaths.databasesDirectory.isDirectory)
            assertTrue(secondPaths.sharedPreferencesDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deletingOneInstanceLeavesAnotherInstancesStorageIntact() {
        val root = Files.createTempDirectory("clonehub-instance-delete").toFile()
        try {
            val layout = VirtualInstanceStorageLayout(root)
            val first = VirtualAppInstance.create(
                "com.example.demo",
                VirtualAppConfiguration(dataDirectoryName = "instance_first")
            )
            val second = VirtualAppInstance.create(
                "com.example.demo",
                VirtualAppConfiguration(dataDirectoryName = "instance_second")
            )
            val firstPaths = layout.prepare(first)
            val secondPaths = layout.prepare(second)

            assertTrue(layout.delete(first))
            assertFalse(firstPaths.instanceRoot.exists())
            assertTrue(secondPaths.instanceRoot.isDirectory)
            assertTrue(secondPaths.filesDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafeInstanceDirectoryNames() {
        val root = Files.createTempDirectory("clonehub-instance-path").toFile()
        try {
            val layout = VirtualInstanceStorageLayout(root)
            val invalid = VirtualAppInstance.create(
                "com.example.demo",
                VirtualAppConfiguration(dataDirectoryName = "instance_safe")
            ).copy(configuration = VirtualAppConfiguration(dataDirectoryName = ".."))
            try {
                layout.pathsFor(invalid)
                throw AssertionError("Expected unsafe directory name to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: path traversal names are not accepted.
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
