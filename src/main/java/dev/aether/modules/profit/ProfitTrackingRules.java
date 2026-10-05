package dev.aether.modules.profit;

import java.util.Locale;

/**
 * Minecraft-free rules used by the profit tracker, kept separate so they can be unit tested.
 */
public final class ProfitTrackingRules {
    private ProfitTrackingRules() {
    }

    /**
     * Parses a colour-stripped scoreboard line such as {@code "Purse: 1,234,567.8 (+50)"} or
     * {@code "Piggy: 12,345"} and returns the balance in whole coins, or -1 if the line holds no balance.
     *
     * <p>The previous parser removed every non-digit, so a fractional purse like {@code 1,234.5} was read
     * as {@code 12345}. Flipping between fractional and whole balances then produced phantom
     * "Purse" gains on the tracker.
     */
    public static long parsePurseLine(String line) {
        if (line == null) {
            return -1L;
        }
        int labelEnd = labelEnd(line, "Purse:");
        if (labelEnd < 0) {
            labelEnd = labelEnd(line, "Piggy:");
        }
        if (labelEnd < 0) {
            return -1L;
        }

        String value = line.substring(labelEnd).trim();
        int space = value.indexOf(' ');
        if (space >= 0) {
            value = value.substring(0, space);
        }
        value = value.replace(",", "");
        int dot = value.indexOf('.');
        if (dot >= 0) {
            value = value.substring(0, dot);
        }
        if (value.isEmpty()) {
            return -1L;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return -1L;
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static int labelEnd(String line, String label) {
        int index = line.indexOf(label);
        return index < 0 ? -1 : index + label.length();
    }

    /**
     * Maps a Skyblock tool id to the crop it farms, or null when the tool does not pin down a single crop
     * (generic hoes, Fungi Cutter, unknown tools). Callers fall back to inventory detection in that case.
     */
    public static String cropForToolId(String skyblockId) {
        if (skyblockId == null || skyblockId.isBlank()) {
            return null;
        }
        String id = skyblockId.toUpperCase(Locale.ROOT);
        if (id.startsWith("THEORETICAL_HOE_WHEAT")) return "Wheat";
        if (id.startsWith("THEORETICAL_HOE_CARROT")) return "Carrot";
        if (id.startsWith("THEORETICAL_HOE_POTATO")) return "Potato";
        if (id.startsWith("THEORETICAL_HOE_WARTS")) return "Nether Wart";
        if (id.startsWith("THEORETICAL_HOE_CANE")) return "Sugar Cane";
        if (id.startsWith("PUMPKIN_DICER")) return "Pumpkin";
        if (id.startsWith("MELON_DICER")) return "Melon Slice";
        if (id.startsWith("CACTUS_KNIFE")) return "Cactus";
        if (id.startsWith("COCO_CHOPPER")) return "Cocoa Beans";
        return null;
    }

    public static boolean isMushroomTool(String skyblockId) {
        return skyblockId != null && skyblockId.toUpperCase(Locale.ROOT).startsWith("FUNGI_CUTTER");
    }

    /**
     * True when a drop pattern matched inside a real server message rather than something a player typed.
     * Player, party, guild and DM lines always put a colon after the sender before the message body, while
     * Hypixel's drop broadcasts ("RARE DROP!", "PET DROP!", shard and pest messages) have none before the
     * trigger. Without this, anyone typing "RARE DROP! Overbloom" in chat was added to your profit.
     */
    public static boolean isSystemMatch(String text, int matchStart) {
        if (text == null || matchStart < 0) {
            return false;
        }
        if (matchStart == 0) {
            return true;
        }
        return text.lastIndexOf(':', matchStart - 1) < 0;
    }
}
