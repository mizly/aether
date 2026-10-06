package dev.aether.util;

import dev.aether.modules.failsafe.FailsafeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class SkyblockItems {
    public static final Set<String> HYPERION_IDS = Set.of("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE", "NECRON_BLADE");
    public static final Set<String> HEALING_WAND_IDS = Set.of(
            "WAND_OF_HEALING", "WAND_OF_MENDING", "WAND_OF_RESTORATION", "WAND_OF_ATONEMENT");
    public static final Set<String> SOUL_WHIP_IDS = Set.of("SOUL_WHIP", "FLAMING_FLAY");

    private static final List<String> HYPERION_NAMES = List.of("hyperion", "astraea", "scylla", "valkyrie", "necron's blade");
    private static final List<String> HEALING_WAND_NAMES = List.of(
            "wand of healing", "wand of mending", "wand of restoration", "wand of atonement");
    private static final List<String> SOUL_WHIP_NAMES = List.of("soul whip", "flaming flay");
    private static final int HOTBAR_SIZE = 9;

    private SkyblockItems() {}

    public static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        var custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return "";
        var tag = custom.copyTag();
        return tag.getString("id").orElseGet(() -> tag.getCompound("ExtraAttributes")
                .flatMap(attributes -> attributes.getString("id")).orElse(""));
    }

    public static String plainName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return TablistUtils.stripColors(stack.getHoverName().getString()).replace(' ', ' ').strip();
    }

    public static boolean isHyperion(ItemStack stack) {
        return matches(stack, HYPERION_IDS, HYPERION_NAMES);
    }

    public static boolean isHealingWand(ItemStack stack) {
        return matches(stack, HEALING_WAND_IDS, HEALING_WAND_NAMES);
    }

    public static boolean isSoulWhip(ItemStack stack) {
        return matches(stack, SOUL_WHIP_IDS, SOUL_WHIP_NAMES);
    }

    static boolean matches(ItemStack stack, Set<String> ids, List<String> baseNames) {
        if (stack == null || stack.isEmpty()) return false;
        return matches(skyblockId(stack), plainName(stack), ids, baseNames);
    }

    // the name only stands in when there is no id, and contains-matching keeps reforged or starred names
    static boolean matches(String id, String name, Set<String> ids, List<String> baseNames) {
        if (id != null && !id.isBlank()) return ids.contains(id.toUpperCase(Locale.ROOT));
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String base : baseNames) {
            if (lower.contains(base)) return true;
        }
        return false;
    }

    static int firstMatchingSlot(int held, List<ItemStack> hotbar, Predicate<ItemStack> matcher) {
        if (held >= 0 && held < hotbar.size() && test(hotbar.get(held), matcher)) return held;
        for (int slot = 0; slot < hotbar.size(); slot++) {
            if (test(hotbar.get(slot), matcher)) return slot;
        }
        return -1;
    }

    public static int findHotbarSlot(Minecraft mc, Predicate<ItemStack> matcher) {
        if (mc == null || mc.player == null) return -1;
        List<ItemStack> hotbar = new ArrayList<>(HOTBAR_SIZE);
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            hotbar.add(mc.player.getInventory().getItem(slot));
        }
        return firstMatchingSlot(FailsafeManager.getCurrentSelectedSlot(mc), hotbar, matcher);
    }

    private static boolean test(ItemStack stack, Predicate<ItemStack> matcher) {
        return stack != null && !stack.isEmpty() && matcher.test(stack);
    }
}
