package dev.aether.config

import java.nio.charset.StandardCharsets
import java.util.Base64

class RewarpPointPair private constructor() {
    @JvmField var name: String? = null
    @JvmField var startX = 0.0
    @JvmField var startY = 0.0
    @JvmField var startZ = 0.0
    @JvmField var startSet = false
    @JvmField var highlightStart = false
    @JvmField var endX = 0.0
    @JvmField var endY = 0.0
    @JvmField var endZ = 0.0
    @JvmField var endSet = false
    @JvmField var highlightEnd = false
    @JvmField var rewarpMode: RewarpMode? = null
    @JvmField var plotTpNumber: String? = null
    @JvmField var holdWUntilWall = false
    @JvmField var aotvAlign = false
    @JvmField var reverseDirection = false

    constructor(config: String?, fallbackIndex: Int) : this() {
        val fallback = defaultPair(fallbackIndex)
        if (config.isNullOrBlank()) {
            copyFrom(fallback)
            return
        }
        val parts = config.split(":")
        if (parts.size < 12 || parts[0] !in setOf(PREFIX_V1, PREFIX_V2, PREFIX_V3, PREFIX_V4, PREFIX_V5, PREFIX_V6, PREFIX_V7)) {
            copyFrom(fallback)
            return
        }
        try {
            name = decodeName(parts[1], fallback.name)
            startX = parts[2].toDouble()
            startY = parts[3].toDouble()
            startZ = parts[4].toDouble()
            startSet = parts[5].toBoolean()
            highlightStart = parts[6].toBoolean()
            endX = parts[7].toDouble()
            endY = parts[8].toDouble()
            endZ = parts[9].toDouble()
            startX = snapToBlockCenter(startX)
            startZ = snapToBlockCenter(startZ)
            endX = snapToBlockCenter(endX)
            endZ = snapToBlockCenter(endZ)
            endSet = parts[10].toBoolean()
            highlightEnd = parts[11].toBoolean()
            reverseDirection = parts[0] == PREFIX_V7 && parts.size >= 17 && parts[16].toBoolean()
            when {
                (parts[0] == PREFIX_V7 || parts[0] == PREFIX_V6) && parts.size >= 16 -> {
                    rewarpMode = RewarpMode.fromConfig(parts[12]); plotTpNumber = parts[13]
                    holdWUntilWall = parts[14].toBoolean(); aotvAlign = parts[15].toBoolean()
                }
                parts[0] == PREFIX_V5 && parts.size >= 15 -> {
                    rewarpMode = RewarpMode.fromConfig(parts[12]); plotTpNumber = parts[13]
                    holdWUntilWall = parts[14].toBoolean(); aotvAlign = false
                }
                parts[0] == PREFIX_V4 && parts.size >= 16 -> {
                    rewarpMode = RewarpMode.fromConfig(parts[12]); plotTpNumber = parts[13]
                    holdWUntilWall = parts[14].toBoolean(); aotvAlign = parts[15].toBoolean()
                }
                parts[0] == PREFIX_V3 && parts.size >= 16 -> {
                    rewarpMode = if (parts[12].toBoolean()) RewarpMode.PLOT_TP else RewarpMode.FLY
                    plotTpNumber = parts[13]; holdWUntilWall = parts[14].toBoolean(); aotvAlign = parts[15].toBoolean()
                }
                parts[0] == PREFIX_V2 && parts.size >= 17 -> {
                    rewarpMode = if (parts[12].toBoolean()) RewarpMode.PLOT_TP else RewarpMode.FLY
                    plotTpNumber = parts[14]; holdWUntilWall = parts[15].toBoolean(); aotvAlign = parts[16].toBoolean()
                }
                else -> {
                    rewarpMode = fallback.rewarpMode; plotTpNumber = fallback.plotTpNumber
                    holdWUntilWall = fallback.holdWUntilWall; aotvAlign = fallback.aotvAlign
                }
            }
            if (name.isNullOrBlank()) name = fallback.name
            if (rewarpMode == null) rewarpMode = fallback.rewarpMode
            if (plotTpNumber.isNullOrBlank()) plotTpNumber = fallback.plotTpNumber
        } catch (_: Exception) {
            copyFrom(fallback)
        }
    }

    fun hasStart(): Boolean = startSet
    fun hasEnd(): Boolean = endSet
    fun displayName(): String = if (name.isNullOrBlank()) defaultName(0) else name!!.trim()

    override fun toString(): String = "$PREFIX_V7:${encodeName(displayName())}:$startX:$startY:$startZ:$startSet:$highlightStart:$endX:$endY:$endZ:$endSet:$highlightEnd:${rewarpMode!!.name}:${sanitizePlotNumber(plotTpNumber)}:$holdWUntilWall:$aotvAlign:$reverseDirection"

    private fun copyFrom(other: RewarpPointPair) {
        name = other.name; startX = other.startX; startY = other.startY; startZ = other.startZ
        startSet = other.startSet; highlightStart = other.highlightStart
        endX = other.endX; endY = other.endY; endZ = other.endZ
        endSet = other.endSet; highlightEnd = other.highlightEnd
        rewarpMode = other.rewarpMode; plotTpNumber = other.plotTpNumber
        holdWUntilWall = other.holdWUntilWall; aotvAlign = other.aotvAlign; reverseDirection = other.reverseDirection
    }

    companion object {
        private const val PREFIX_V1 = "v1"
        private const val PREFIX_V2 = "v2"
        private const val PREFIX_V3 = "v3"
        private const val PREFIX_V4 = "v4"
        private const val PREFIX_V5 = "v5"
        private const val PREFIX_V6 = "v6"
        private const val PREFIX_V7 = "v7"

        @JvmStatic
        fun defaultPair(index: Int): RewarpPointPair = RewarpPointPair().apply {
            name = defaultName(index); highlightStart = true; highlightEnd = true
            rewarpMode = RewarpMode.FLY; plotTpNumber = "0"
        }

        @JvmStatic
        fun fromLegacyConfig(): RewarpPointPair = defaultPair(0).apply {
            startX = snapToBlockCenter(AetherConfig.REWARP_START_X.get())
            startY = AetherConfig.REWARP_START_Y.get()
            startZ = snapToBlockCenter(AetherConfig.REWARP_START_Z.get())
            startSet = AetherConfig.REWARP_START_POS_SET.get()
            highlightStart = AetherConfig.REWARP_HIGHLIGHT_START.get()
            endX = snapToBlockCenter(AetherConfig.REWARP_END_X.get())
            endY = AetherConfig.REWARP_END_Y.get()
            endZ = snapToBlockCenter(AetherConfig.REWARP_END_Z.get())
            endSet = AetherConfig.REWARP_END_POS_SET.get()
            highlightEnd = AetherConfig.REWARP_HIGHLIGHT_END.get()
            rewarpMode = if (AetherConfig.ENABLE_PLOT_TP_REWARP.get()) RewarpMode.PLOT_TP else RewarpMode.FLY
            plotTpNumber = AetherConfig.PLOT_TP_NUMBER.get()
            holdWUntilWall = AetherConfig.HOLD_W_UNTIL_WALL.get()
            aotvAlign = AetherConfig.REWARP_AOTV_ALIGN.get()
        }

        @JvmStatic
        fun defaultConfig(index: Int): String = defaultPair(index).toString()

        @JvmStatic
        fun snapToBlockCenter(coordinate: Double): Double = kotlin.math.floor(coordinate) + 0.5

        private fun defaultName(index: Int): String = "Rewarp ${index + 1}"
        private fun encodeName(name: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(StandardCharsets.UTF_8))
        private fun decodeName(encoded: String, fallback: String?): String = try {
            String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        } catch (_: Exception) { fallback ?: defaultName(0) }
        private fun sanitizePlotNumber(value: String?): String = value?.takeIf { it.isNotBlank() }?.trim()?.replace(":", "") ?: "0"
    }
}
