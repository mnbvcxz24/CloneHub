package com.moldyhub.clonehub

import android.content.Context
import com.moldyhub.clonehub.cve.InstanceState
import com.moldyhub.clonehub.cve.VirtualAppConfiguration
import com.moldyhub.clonehub.cve.VirtualAppInstance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists CloneHub instance profiles and lifecycle metadata.
 * The private directory created here belongs to CloneHub; it is not target-app
 * storage and does not provide app or process isolation.
 */
class VirtualAppInstanceStore(context: Context) : VirtualAppInstanceRepository {
    private val preferences = context.getSharedPreferences("clonehub_instances", Context.MODE_PRIVATE)
    private val legacyPreferences = context.getSharedPreferences("clonehub_library", Context.MODE_PRIVATE)
    private val rootDirectory = File(context.filesDir, "instances")

    @Synchronized
    fun getForPackage(packageName: String): List<VirtualAppInstance> =
        readAll().filter { it.packageName == packageName }

    @Synchronized
    override fun getById(instanceId: String): VirtualAppInstance? =
        readAll().firstOrNull { it.instanceId == instanceId }

    @Synchronized
    override fun update(instance: VirtualAppInstance): VirtualAppInstance {
        val current = readAll()
        check(current.any { it.instanceId == instance.instanceId }) {
            "Instance not found: ${instance.instanceId}"
        }
        writeAll(current.map {
            if (it.instanceId == instance.instanceId) instance else it
        })
        return instance
    }

    @Synchronized
    fun create(packageName: String, displayName: String): VirtualAppInstance {
        val directoryName = "instance_${java.util.UUID.randomUUID()}"
        val instance = VirtualAppInstance.create(
            packageName = packageName,
            configuration = VirtualAppConfiguration(displayName, directoryName)
        )
        val directory = File(rootDirectory, directoryName)
        check(directory.mkdirs() || directory.isDirectory) { "Could not create instance profile directory" }
        writeAll(readAll() + instance)
        return instance
    }

    @Synchronized
    fun remove(instanceId: String) {
        val current = readAll()
        val removed = current.firstOrNull { it.instanceId == instanceId } ?: return
        writeAll(current.filterNot { it.instanceId == instanceId })
        removed.configuration.dataDirectoryName?.let { File(rootDirectory, it).deleteRecursively() }
    }

    @Synchronized
    fun removeAllForPackage(packageName: String) {
        val current = readAll()
        val removed = current.filter { it.packageName == packageName }
        writeAll(current.filterNot { it.packageName == packageName })
        removed.forEach { instance ->
            instance.configuration.dataDirectoryName?.let { File(rootDirectory, it).deleteRecursively() }
        }
    }

    private fun readAll(): List<VirtualAppInstance> {
        val raw = preferences.getString(KEY_INSTANCES, null)
        if (raw == null) return migrateLegacyProfiles()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val displayName = item.optString("displayName").takeIf { it.isNotBlank() }
                    val directoryName = item.optString("dataDirectoryName").takeIf { it.isNotBlank() }
                    add(VirtualAppInstance(
                        instanceId = item.getString("instanceId"),
                        packageName = item.getString("packageName"),
                        state = InstanceState.valueOf(item.getString("state")),
                        configuration = VirtualAppConfiguration(displayName, directoryName)
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Preserve profiles created by the first UI, which stored display labels only. */
    private fun migrateLegacyProfiles(): List<VirtualAppInstance> {
        val migrated = mutableListOf<VirtualAppInstance>()
        legacyPreferences.all.forEach { (key, value) ->
            if (!key.startsWith("instances_")) return@forEach
            val packageName = key.removePrefix("instances_")
            val labels = value as? Set<*> ?: return@forEach
            labels.filterIsInstance<String>().forEach { label ->
                try {
                    val directoryName = "instance_" + java.util.UUID.randomUUID().toString()
                    val instance = VirtualAppInstance.create(
                        packageName, VirtualAppConfiguration(label, directoryName)
                    )
                    val directory = File(rootDirectory, directoryName)
                    if (directory.mkdirs() || directory.isDirectory) migrated.add(instance)
                } catch (_: IllegalArgumentException) {
                    // Ignore invalid legacy records rather than blocking startup.
                }
            }
        }
        writeAll(migrated)
        return migrated
    }
    private fun writeAll(instances: List<VirtualAppInstance>) {
        val array = JSONArray()
        instances.forEach { instance ->
            array.put(JSONObject()
                .put("instanceId", instance.instanceId)
                .put("packageName", instance.packageName)
                .put("state", instance.state.name)
                .put("displayName", instance.configuration.displayName ?: "")
                .put("dataDirectoryName", instance.configuration.dataDirectoryName ?: ""))
        }
        check(preferences.edit().putString(KEY_INSTANCES, array.toString()).commit()) {
            "Could not save instance profiles"
        }
    }

    private companion object {
        const val KEY_INSTANCES = "saved_instances"
    }
}
