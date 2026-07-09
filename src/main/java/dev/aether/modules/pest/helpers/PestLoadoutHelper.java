package dev.aether.modules.pest.helpers;

import dev.aether.mixin.AccessorInventory;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

final class PestLoadoutHelper {
    private static final Map<String, Float> VACUUM_RANGES = Map.of(
            "Skymart Vacuum", 5f,
            "Turbo Vacuum", 7.5f,
            "Hyper Vacuum", 10f,
            "InfiniVacuum", 12.5f,
            "InfiniVacuum Hooverius", 15F);

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
        for (Map.Entry<String, Float> entry : VACUUM_RANGES.entrySet()) {
            if (name.contains(entry.getKey())) {
                return entry.getValue() * 0.9f;
            }
        }
        return 7.5f * 0.8f;
    }
}
