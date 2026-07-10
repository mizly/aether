package dev.aether.modules.visitor;

import java.util.List;

/**
 * Garden visitor rarity, ordered lowest to highest. Determined from the
 * color code of the visitor's name in the tab list.
 */
public enum VisitorRarity {
    UNKNOWN("Unknown"),
    UNCOMMON("Uncommon"),
    RARE("Rare"),
    LEGENDARY("Legendary"),
    MYTHIC("Mythic"),
    SPECIAL("Special");

    private final String displayName;

    VisitorRarity(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static VisitorRarity fromColorCode(char code) {
        return switch (Character.toLowerCase(code)) {
            case 'a' -> UNCOMMON;
            case '9' -> RARE;
            case '6' -> LEGENDARY;
            case 'd' -> MYTHIC;
            case 'c' -> SPECIAL;
            default -> UNKNOWN;
        };
    }

    /** Dropdown options for the minimum-rarity setting (UNKNOWN excluded). */
    public static List<String> selectableNames() {
        return List.of(UNCOMMON.displayName, RARE.displayName, LEGENDARY.displayName,
                MYTHIC.displayName, SPECIAL.displayName);
    }

    /** Maps a selectable-names index (0 = Uncommon) back to a rarity. */
    public static VisitorRarity fromSelectableIndex(int index) {
        int ordinal = index + UNCOMMON.ordinal();
        if (ordinal < UNCOMMON.ordinal() || ordinal > SPECIAL.ordinal()) {
            return UNCOMMON;
        }
        return values()[ordinal];
    }
}
