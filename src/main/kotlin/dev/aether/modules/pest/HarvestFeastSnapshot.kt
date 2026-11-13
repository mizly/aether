package dev.aether.modules.pest

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

class HarvestFeastSnapshot(
    private val activeValue: Boolean,
    activeCropsValue: List<String>,
    nextCropTimestampsValue: Map<String, Long>,
    private val nextFetchMsValue: Long
) {
    private val activeCropsValue: List<String> = java.util.List.copyOf(activeCropsValue)
    private val nextCropTimestampsValue: Map<String, Long> = Collections.unmodifiableMap(LinkedHashMap(nextCropTimestampsValue))

    fun active(): Boolean = activeValue
    fun activeCrops(): List<String> = activeCropsValue
    fun nextCropTimestamps(): Map<String, Long> = nextCropTimestampsValue
    fun nextFetchMs(): Long = nextFetchMsValue

    override fun equals(other: Any?): Boolean = other is HarvestFeastSnapshot && activeValue == other.activeValue && activeCropsValue == other.activeCropsValue && nextCropTimestampsValue == other.nextCropTimestampsValue && nextFetchMsValue == other.nextFetchMsValue
    override fun hashCode(): Int = java.util.Objects.hash(activeValue, activeCropsValue, nextCropTimestampsValue, nextFetchMsValue)
    override fun toString(): String = "HarvestFeastSnapshot[active=$activeValue, activeCrops=$activeCropsValue, nextCropTimestamps=$nextCropTimestampsValue, nextFetchMs=$nextFetchMsValue]"

    companion object {
        @JvmStatic
        fun parse(responseBody: String, now: Long, fallbackPollMs: Long): HarvestFeastSnapshot {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val complete = json.has("complete") && json.get("complete").asBoolean
            val current: JsonArray? = if (json.has("current")) json.getAsJsonArray("current") else null
            if (!complete || current == null || current.isEmpty) return HarvestFeastSnapshot(false, java.util.List.of(), java.util.Map.of(), now + fallbackPollMs)
            val crops = ArrayList<String>(current.size())
            for (element in current) crops.add(DynamicPestPolicy.normalizeCrop(element.asString))
            var earliest = Long.MAX_VALUE
            val next = LinkedHashMap<String, Long>()
            if (json.has("next")) {
                for ((key, value) in json.getAsJsonObject("next").entrySet()) {
                    if (value.isJsonNull) continue
                    val timestampMs = value.asLong * 1000L
                    next[DynamicPestPolicy.normalizeCrop(key)] = timestampMs
                    if (timestampMs > now && timestampMs < earliest) earliest = timestampMs
                }
            }
            val nextFetch = if (earliest == Long.MAX_VALUE) now + fallbackPollMs else earliest
            return HarvestFeastSnapshot(true, crops, next, nextFetch)
        }
    }
}
