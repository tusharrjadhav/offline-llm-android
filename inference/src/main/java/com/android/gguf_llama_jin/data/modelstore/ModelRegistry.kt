package com.android.gguf_llama_jin.data.modelstore

import android.content.Context
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.core.ModelRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ModelRegistry(private val context: Context) {
    private val registryFile: File
        get() = File(context.filesDir, Constants.REGISTRY_FILE)

    suspend fun listInstalled(): List<InstalledModel> = withContext(Dispatchers.IO) {
        if (!registryFile.exists()) return@withContext emptyList()
        val raw = registryFile.readText().trim()
        if (raw.isBlank()) return@withContext emptyList()

        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(item.toInstalledModel())
            }
        }
    }

    suspend fun upsert(model: InstalledModel) = withContext(Dispatchers.IO) {
        val current = listInstalled().toMutableList()
        val idx = current.indexOfFirst {
            it.id == model.id && it.variant == model.variant && it.runtime == model.runtime
        }
        if (idx >= 0) current[idx] = model else current.add(model)

        val arr = JSONArray()
        current.forEach { arr.put(it.toJson()) }
        registryFile.writeText(arr.toString())
    }

    suspend fun remove(id: String, runtime: ModelRuntime, variant: String) = withContext(Dispatchers.IO) {
        val current = listInstalled().filterNot {
            it.id == id && it.variant == variant && it.runtime == runtime
        }
        val arr = JSONArray()
        current.forEach { arr.put(it.toJson()) }
        registryFile.writeText(arr.toString())
    }

    private fun JSONObject.toInstalledModel(): InstalledModel {
        val runtimeRaw = optString("runtime").ifBlank { "LLAMA_CPP_GGUF" }
        val runtime = runCatching { ModelRuntime.valueOf(runtimeRaw) }.getOrDefault(ModelRuntime.LLAMA_CPP_GGUF)
        val variant = when {
            has("variant") && !isNull("variant") -> optString("variant")
            has("quant") && !isNull("quant") -> optString("quant")
            else -> "default"
        }
        return InstalledModel(
            id = optString("id"),
            runtime = runtime,
            variant = variant,
            path = optString("path"),
            sizeBytes = optLong("sizeBytes"),
            sha256 = if (has("sha256") && !isNull("sha256")) optString("sha256") else null,
            installedAt = optLong("installedAt"),
            lastUsedAt = optLong("lastUsedAt"),
            assetDir = if (has("assetDir") && !isNull("assetDir")) optString("assetDir") else null
        )
    }

    private fun InstalledModel.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("runtime", runtime.name)
            .put("variant", variant)
            .put("path", path)
            .put("sizeBytes", sizeBytes)
            .put("sha256", sha256)
            .put("installedAt", installedAt)
            .put("lastUsedAt", lastUsedAt)
            .put("assetDir", assetDir)
    }
}
