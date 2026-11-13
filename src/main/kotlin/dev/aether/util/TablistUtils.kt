package dev.aether.util

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.world.level.GameType
import java.util.ArrayList
import java.util.Comparator
import java.util.ConcurrentModificationException
import java.util.regex.Pattern

// every module reading the tab list should come through here instead of redoing iteration, colour stripping and sorting
class TablistUtils private constructor() {
    companion object {
        private val STRIP_HEX_COLOR = Pattern.compile("(?i)\u00A7x(?:\u00A7[0-9a-f]){6}")
        private val STRIP_COLOR = Pattern.compile("(?i)\u00A7[0-9a-fk-or]")
        private val CACHE_LOCK = Any()
        private var rawCacheTick = Int.MIN_VALUE
        private var rawCacheConnectionId = 0
        private var rawCacheLines: List<String> = emptyList()
        private var sortedCacheTick = Int.MIN_VALUE
        private var sortedCacheConnectionId = 0
        private var sortedCacheInfo: List<PlayerInfo> = emptyList()
        private var sortedCacheLines: List<String> = emptyList()

        @JvmStatic
        fun stripColors(s: String?): String {
            if (s == null || s.isEmpty()) return ""
            var stripped = STRIP_HEX_COLOR.matcher(s).replaceAll("")
            stripped = STRIP_COLOR.matcher(stripped).replaceAll("")
            return stripped.replace("\u00A7", "")
        }

        // same order the vanilla overlay uses
        @JvmStatic
        fun getTabLines(client: Minecraft): List<String> {
            if (client.getConnection() == null || client.player == null) return emptyList()
            val tick = client.player!!.tickCount
            val connectionId = System.identityHashCode(client.getConnection())
            synchronized(CACHE_LOCK) {
                if (tick == sortedCacheTick && connectionId == sortedCacheConnectionId) return sortedCacheLines
                val sortedInfo = buildSortedPlayerInfo(client, connectionId)
                val lines = ArrayList<String>(sortedInfo.size)
                for (info in sortedInfo) lines.add(cleanLine(info))
                sortedCacheTick = tick
                sortedCacheConnectionId = connectionId
                sortedCacheInfo = sortedInfo.toList()
                sortedCacheLines = lines.toList()
                return sortedCacheLines
            }
        }

        // unsorted
        @JvmStatic
        fun getRawTabLines(client: Minecraft): List<String> {
            if (client.getConnection() == null || client.player == null) return emptyList()
            val tick = client.player!!.tickCount
            val connectionId = System.identityHashCode(client.getConnection())
            synchronized(CACHE_LOCK) {
                if (tick == rawCacheTick && connectionId == rawCacheConnectionId) return rawCacheLines
                val listedPlayers = copyListedOnlinePlayers(client)
                if (listedPlayers == null) return if (connectionId == rawCacheConnectionId) rawCacheLines else emptyList()
                val lines = ArrayList<String>(listedPlayers.size)
                for (info in listedPlayers) lines.add(cleanLine(info))
                rawCacheTick = tick
                rawCacheConnectionId = connectionId
                rawCacheLines = lines.toList()
                return rawCacheLines
            }
        }

        // case-insensitive
        @JvmStatic
        fun findLine(client: Minecraft, substring: String): String? {
            val lower = substring.lowercase()
            for (line in getRawTabLines(client)) if (line.lowercase().contains(lower)) return line
            return null
        }

        // raw PlayerInfo entries in vanilla tab order
        @JvmStatic
        fun getSortedPlayerInfo(client: Minecraft): List<PlayerInfo> {
            if (client.getConnection() == null || client.player == null) return emptyList()
            val tick = client.player!!.tickCount
            val connectionId = System.identityHashCode(client.getConnection())
            synchronized(CACHE_LOCK) {
                if (tick == sortedCacheTick && connectionId == sortedCacheConnectionId) return sortedCacheInfo
            }
            getTabLines(client)
            synchronized(CACHE_LOCK) { return sortedCacheInfo }
        }

        private fun cleanLine(info: PlayerInfo): String {
            val displayName = info.getTabListDisplayName()
            val raw = if (displayName != null) displayName.string
                else if (info.getProfile() != null) info.getProfile().name() else ""
            return stripColors(raw).replace('\u00A0', ' ').trim()
        }

        private fun buildSortedPlayerInfo(client: Minecraft, connectionId: Int): List<PlayerInfo> {
            val sorted = copyListedOnlinePlayers(client)
                ?: return if (connectionId == sortedCacheConnectionId) sortedCacheInfo else emptyList()
            sorted.sortWith(Comparator.comparingInt<PlayerInfo> { if (it.getGameMode() == GameType.SPECTATOR) 1 else 0 }
                .thenComparingInt { -it.getTabListOrder() }
                .thenComparing { info -> val team = info.getTeam(); if (team != null) team.getName() else "" }
                .thenComparing({ info -> if (info.getProfile() != null) info.getProfile().name() else "" }, Comparator.naturalOrder()))
            return sorted
        }

        private fun copyListedOnlinePlayers(client: Minecraft): MutableList<PlayerInfo>? {
            val connection = client.getConnection() ?: return null
            val listedPlayers = connection.getListedOnlinePlayers()
            for (attempt in 0 until 3) {
                try {
                    return ArrayList(listedPlayers)
                } catch (_: ConcurrentModificationException) {
                    Thread.yield()
                }
            }
            return null
        }
    }
}
