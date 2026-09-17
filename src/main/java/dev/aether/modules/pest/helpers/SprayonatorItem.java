package dev.aether.modules.pest.helpers;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class SprayonatorItem {
    private static final Set<String> IDS = Set.of("SPRAYONATOR", "JUICY_SPRAYONATOR", "SALTY_SPRAYONATOR");
    private static final Set<String> NAMES = Set.of("sprayonator", "juicy sprayonator", "salty sprayonator");
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern MATERIAL = Pattern.compile("(?i)^selected\\s+material\\s*:\\s*(.+)$");

    private SprayonatorItem() {}

    static boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var custom = stack.get(DataComponents.CUSTOM_DATA);
        String id = "";
        if (custom != null) {
            var tag = custom.copyTag();
            id = tag.getString("id").orElseGet(() -> tag.getCompound("ExtraAttributes")
                    .flatMap(attributes -> attributes.getString("id")).orElse(""));
        }
        return matches(id, stack.getHoverName().getString());
    }

    static boolean matches(String id, String name) {
        if (id != null && !id.isBlank()) return IDS.contains(id.toUpperCase(Locale.ROOT));
        return NAMES.contains(clean(name).toLowerCase(Locale.ROOT));
    }

    static String selectedMaterial(ItemStack stack) {
        if (!matches(stack)) return null;
        var lore = stack.get(DataComponents.LORE);
        return selectedMaterial(lore == null ? List.of() : lore.lines().stream().map(Component::getString).toList());
    }

    static String selectedMaterial(List<String> lore) {
        for (String line : lore) {
            var matcher = MATERIAL.matcher(clean(line));
            if (matcher.matches()) {
                String material = matcher.group(1).trim();
                return material.equalsIgnoreCase("none") ? null : material;
            }
        }
        return null;
    }

    private static String clean(String text) {
        return FORMATTING.matcher(text == null ? "" : text).replaceAll("")
                .replace('\u00a0', ' ').strip();
    }
}
