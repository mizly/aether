package dev.aether.modules.profit

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.time.LocalDate

class ProfitPersistence {
    data class DailySnapshot(val counts: Map<String, Long>, val sprayQuantity: Long, val resetDate: String)

    fun saveLifetime(lifetimeCounts: Map<String, Long>) {
        try {
            FileWriter(LIFETIME_FILE).use { GSON.toJson(lifetimeCounts, it) }
        } catch (e: java.io.IOException) {
            e.printStackTrace()
        }
    }

    fun loadLifetime(lifetimeCounts: MutableMap<String, Long>, onLoaded: Runnable) {
        if (!LIFETIME_FILE.exists()) return
        try {
            FileReader(LIFETIME_FILE).use { reader ->
                val data: Map<String, Long>? = GSON.fromJson(reader, COUNTS_TYPE)
                if (data != null) {
                    lifetimeCounts.clear()
                    lifetimeCounts.putAll(data)
                    onLoaded.run()
                }
            }
        } catch (e: Exception) {
            System.err.println("[Aether] Failed to load lifetime profit data: ${e.message}")
        }
    }

    fun saveDaily(dailyCounts: Map<String, Long>, sprayQuantity: Long, resetDate: String) {
        try {
            FileWriter(DAILY_FILE).use { writer ->
                val data = JsonObject()
                val countsObject = JsonObject()
                dailyCounts.forEach { (key, value) -> countsObject.addProperty(key, value) }
                data.add("counts", countsObject)
                data.addProperty("sprayQuantity", sprayQuantity)
                data.addProperty("resetDate", resetDate)
                GSON.toJson(data, writer)
            }
        } catch (e: java.io.IOException) {
            e.printStackTrace()
        }
    }

    fun loadDaily(): DailySnapshot? {
        if (!DAILY_FILE.exists()) return null
        try {
            FileReader(DAILY_FILE).use { reader ->
                val data = GSON.fromJson(reader, JsonObject::class.java) ?: return null
                val counts = linkedMapOf<String, Long>()
                val countsElement = data.get("counts")
                if (countsElement != null && countsElement.isJsonObject) {
                    countsElement.asJsonObject.entrySet().forEach { (key, element) ->
                        if (element != null && element.isJsonPrimitive) {
                            try { counts[key] = element.asLong } catch (_: Exception) { }
                        }
                    }
                }
                var sprayQuantity = 0L
                data.get("sprayQuantity")?.takeUnless { it.isJsonNull }?.let {
                    try { sprayQuantity = it.asLong } catch (_: Exception) { }
                }
                var resetDate = getCurrentDateString()
                data.get("resetDate")?.takeUnless { it.isJsonNull }?.let {
                    try { resetDate = it.asString } catch (_: Exception) { }
                }
                return DailySnapshot(counts, sprayQuantity, resetDate)
            }
        } catch (e: Exception) {
            System.err.println("[Aether] Failed to load daily profit data: ${e.message}")
            return null
        }
    }

    fun getCurrentDateString(): String = LocalDate.now().toString()

    companion object {
        private val LIFETIME_FILE: File = FabricLoader.getInstance().configDir.resolve("aether_profit_lifetime.json").toFile()
        private val DAILY_FILE: File = FabricLoader.getInstance().configDir.resolve("aether_profit_daily.json").toFile()
        private val GSON = GsonBuilder().setPrettyPrinting().create()
        private val COUNTS_TYPE = object : TypeToken<Map<String, Long>>() {}.type
    }
}
