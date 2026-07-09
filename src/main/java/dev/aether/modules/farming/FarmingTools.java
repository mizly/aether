package dev.aether.modules.farming;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

public final class FarmingTools {
    private static final List<String> NAME_KEYWORDS = List.of(
            "hoe",
            "dicer",
            "knife",
            "chopper",
            "cutter",
            "sickle",
            "shovel",
            "axe");

    private FarmingTools() {
    }

    public static List<String> nameKeywords() {
        return NAME_KEYWORDS;
    }

    public static boolean isFarmingTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        return isFarmingToolName(stack.getHoverName().getString());
    }

    public static boolean isFarmingToolName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        String normalizedName = name.toLowerCase(Locale.ROOT);
        for (String keyword : NAME_KEYWORDS) {
            if (normalizedName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
