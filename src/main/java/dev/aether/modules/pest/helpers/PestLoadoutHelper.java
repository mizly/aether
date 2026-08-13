package dev.aether.modules.pest.helpers;

import dev.aether.mixin.AccessorInventory;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

final class PestLoadoutHelper {
    private static final List<Map.Entry<String, Float>> VACUUM_RANGES = List.of(
        Map.entry("InfiniVacuum Hooverius", 15f),
        Map.entry("InfiniVacuum", 12.5f),
        Map.entry("Hyper Vacuum", 10f),
        Map.entry("Turbo Vacuum", 7.5f),
        Map.entry("Skymart Vacuum", 5f)
    );

    private PestLoadoutHelper() {
    }

    static int findVacuumHotbarSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }

        ItemStack current = client.player.getMainHandItem();
        if (!current.isEmpty() && current.getHoverName().getString().toLowerCase().contains("vacuum")) {
            return ((AccessorInventory) client.player.getInventory()).getSelected();
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase().contains("vacuum")) {
                return i;
            }
        }
        return -1;
    }

    /** Returns [lowest-rarity stun slot, highest-rarity kill slot]. */
    static int[] findAutomaticVacuumSlots(Minecraft client) {
        if (client.player == null) return new int[] {-1, -1};
        int lowestSlot = -1, highestSlot = -1;
        int lowestQuality = Integer.MAX_VALUE, highestQuality = Integer.MIN_VALUE;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !isVacuum(stack)) continue;
            int quality = vacuumQuality(stack);
            if (quality < lowestQuality) { lowestQuality = quality; lowestSlot = slot; }
            if (quality > highestQuality) { highestQuality = quality; highestSlot = slot; }
        }
        if (lowestSlot < 0) return new int[] {-1, -1};
        // One vacuum is deliberately used for both stun and kill.
        return new int[] {lowestSlot, highestSlot < 0 ? lowestSlot : highestSlot};
    }

    private static boolean isVacuum(ItemStack stack) {
        return stack.getHoverName().getString().toLowerCase().contains("vacuum");
    }

    private static int vacuumQuality(ItemStack stack) {
        String name = stack.getHoverName().getString().toLowerCase();
        int tier = name.contains("hooverius") ? 5
                : name.contains("infinivacuum") ? 4
                : name.contains("hyper vacuum") ? 3
                : name.contains("turbo vacuum") ? 2
                : name.contains("skymart vacuum") ? 1 : 0;
        return stack.getRarity().ordinal() * 100 + tier;
    }

    static int findLassoHotbarSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }

        ItemStack current = client.player.getMainHandItem();
        if (isLasso(current)) {
            return ((AccessorInventory) client.player.getInventory()).getSelected();
        }

        for (int i = 0; i < 9; i++) {
            if (isLasso(client.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isLasso(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getHoverName().getString().toLowerCase().contains("lasso");
    }

    static int findAotvHotbarSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }

        ItemStack current = client.player.getMainHandItem();
        if (!current.isEmpty()) {
            String name = current.getHoverName().getString();
            if (name.contains("Aspect of the Void") || name.contains("Aspect of the End")) {
                return ((AccessorInventory) client.player.getInventory()).getSelected();
            }
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String name = stack.getHoverName().getString();
                if (name.contains("Aspect of the Void") || name.contains("Aspect of the End")) {
                    return i;
                }
            }
        }
        return -1;
    }

    static float detectVacuumRange(Minecraft client, int slot) {
        ItemStack stack = client.player.getInventory().getItem(slot);
        if (stack.isEmpty()) {
            return 7.5f * 0.8f;
        }

        String name = stack.getHoverName().getString().replaceAll("(?i)\\u00A7.", "").trim();
        for (Map.Entry<String, Float> entry : VACUUM_RANGES) {
            if (name.contains(entry.getKey())) {
                return entry.getValue() * 0.9f;
            }
        }
        return 7.5f * 0.8f;
    }
}
