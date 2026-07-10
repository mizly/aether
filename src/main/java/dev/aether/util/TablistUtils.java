package dev.aether.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Centralized tab-list reading helpers.
 * Every module that inspects the player tab list should use these methods
 * instead of re-implementing iteration, color stripping, and sorting.
 */
public final class TablistUtils {

    private static final Pattern STRIP_HEX_COLOR = Pattern.compile("(?i)\u00A7x(?:\u00A7[0-9a-f]){6}");
    private static final Pattern STRIP_COLOR = Pattern.compile("(?i)\u00A7[0-9a-fk-or]");
    private static final Object CACHE_LOCK = new Object();

    private static int rawCacheTick = Integer.MIN_VALUE;
    private static int rawCacheConnectionId = 0;
    private static List<String> rawCacheLines = List.of();

    private static int sortedCacheTick = Integer.MIN_VALUE;
    private static int sortedCacheConnectionId = 0;
    private static List<PlayerInfo> sortedCacheInfo = List.of();
    private static List<String> sortedCacheLines = List.of();

    private TablistUtils() {}

    /** Strip Minecraft color codes from a string. */
    public static String stripColors(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }

        String stripped = STRIP_HEX_COLOR.matcher(s).replaceAll("");
        stripped = STRIP_COLOR.matcher(stripped).replaceAll("");
        return stripped.replace("\u00A7", "");
    }

    /**
     * Returns tab-list entries in the same order the vanilla overlay uses.
     */
    public static List<String> getTabLines(Minecraft client) {
        if (client.getConnection() == null || client.player == null) return List.of();

        int tick = client.player.tickCount;
        int connectionId = System.identityHashCode(client.getConnection());
        synchronized (CACHE_LOCK) {
            if (tick == sortedCacheTick && connectionId == sortedCacheConnectionId) {
                return sortedCacheLines;
            }

            List<PlayerInfo> sortedInfo = buildSortedPlayerInfo(client, connectionId);
            List<String> lines = new ArrayList<>(sortedInfo.size());
            for (PlayerInfo info : sortedInfo) {
                lines.add(cleanLine(info));
            }

            sortedCacheTick = tick;
            sortedCacheConnectionId = connectionId;
            sortedCacheInfo = List.copyOf(sortedInfo);
            sortedCacheLines = List.copyOf(lines);
            return sortedCacheLines;
        }
    }

    /**
     * Returns tab-list entries without sorting.
     */
    public static List<String> getRawTabLines(Minecraft client) {
        if (client.getConnection() == null || client.player == null) return List.of();

        int tick = client.player.tickCount;
        int connectionId = System.identityHashCode(client.getConnection());
        synchronized (CACHE_LOCK) {
            if (tick == rawCacheTick && connectionId == rawCacheConnectionId) {
                return rawCacheLines;
            }

            List<PlayerInfo> listedPlayers = copyListedOnlinePlayers(client);
            if (listedPlayers == null) {
                return connectionId == rawCacheConnectionId ? rawCacheLines : List.of();
            }

            List<String> lines = new ArrayList<>(listedPlayers.size());
            for (PlayerInfo info : listedPlayers) {
                lines.add(cleanLine(info));
            }

            rawCacheTick = tick;
            rawCacheConnectionId = connectionId;
            rawCacheLines = List.copyOf(lines);
            return rawCacheLines;
        }
    }

    /**
     * Returns tab-list entries in vanilla order with color codes preserved.
     */
    public static List<String> getColoredTabLines(Minecraft client) {
        List<PlayerInfo> sortedInfo = getSortedPlayerInfo(client);
        List<String> lines = new ArrayList<>(sortedInfo.size());
        for (PlayerInfo info : sortedInfo) {
            lines.add(rawLine(info));
        }
        return lines;
    }

    /**
     * Returns the first tab-list line containing the substring, case-insensitive.
     */
    public static String findLine(Minecraft client, String substring) {
        String lower = substring.toLowerCase();
        for (String line : getRawTabLines(client)) {
            if (line.toLowerCase().contains(lower)) return line;
        }
        return null;
    }

    /**
     * Returns the raw PlayerInfo entries in vanilla tab order.
     */
    public static List<PlayerInfo> getSortedPlayerInfo(Minecraft client) {
        if (client.getConnection() == null || client.player == null) return List.of();

        int tick = client.player.tickCount;
        int connectionId = System.identityHashCode(client.getConnection());
        synchronized (CACHE_LOCK) {
            if (tick == sortedCacheTick && connectionId == sortedCacheConnectionId) {
                return sortedCacheInfo;
            }
        }

        getTabLines(client);
        synchronized (CACHE_LOCK) {
            return sortedCacheInfo;
        }
    }

    private static String cleanLine(PlayerInfo info) {
        return stripColors(rawLine(info)).trim();
    }

    private static String rawLine(PlayerInfo info) {
        String raw;
        if (info.getTabListDisplayName() != null) {
            raw = info.getTabListDisplayName().getString();
        } else if (info.getProfile() != null) {
            raw = info.getProfile().name();
        } else {
            raw = "";
        }
        return raw.replace('\u00A0', ' ').trim();
    }

    private static List<PlayerInfo> buildSortedPlayerInfo(Minecraft client, int connectionId) {
        List<PlayerInfo> sorted = copyListedOnlinePlayers(client);
        if (sorted == null) {
            return connectionId == sortedCacheConnectionId ? sortedCacheInfo : List.of();
        }

        sorted.sort(Comparator
                .<PlayerInfo>comparingInt(info -> info.getGameMode() == GameType.SPECTATOR ? 1 : 0)
                .thenComparingInt(info -> -info.getTabListOrder())
                .thenComparing(info -> {
                    var team = info.getTeam();
                    return team != null ? team.getName() : "";
                })
                .thenComparing(info -> info.getProfile() != null ? info.getProfile().name() : "",
                        Comparator.naturalOrder()));
        return sorted;
    }

    private static List<PlayerInfo> copyListedOnlinePlayers(Minecraft client) {
        Collection<PlayerInfo> listedPlayers = client.getConnection().getListedOnlinePlayers();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return new ArrayList<>(listedPlayers);
            } catch (ConcurrentModificationException ignored) {
                Thread.yield();
            }
        }
        return null;
    }
}
