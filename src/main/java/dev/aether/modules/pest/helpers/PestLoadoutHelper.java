package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

final class PestLoadoutHelper {
    private PestLoadoutHelper() {
    }

    static int findVacuumHotbarSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }

        ItemStack current = client.player.getMainHandItem();
        if (!current.isEmpty() && current.getHoverName().getString().toLowerCase(Locale.ROOT).contains("vacuum")) {
            return ((AccessorInventory) client.player.getInventory()).getSelected();
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains("vacuum")) {
                return i;
            }
        }
        return -1;
    }

    // returns [lowest-rarity stun slot, highest-rarity kill slot]
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

    static boolean isVacuum(ItemStack stack) {
        return !stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains("vacuum");
    }

    private static int vacuumQuality(ItemStack stack) {
        VacuumProfile profile = vacuumProfile(stack);
        return (int) (profile.range() * 100) + profile.rarity();
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
                && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains("lasso");
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
        if (client.player == null || slot < 0 || slot >= 9) return 4.5f;
        ItemStack stack = client.player.getInventory().getItem(slot);
        if (stack.isEmpty()) return 4.5f;
        return vacuumProfile(stack).effectiveRange(AetherConfig.RESPECT_VACUUM_TRUE_RANGE.get());
    }

    private static VacuumProfile vacuumProfile(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        var custom = stack.get(DataComponents.CUSTOM_DATA);
        boolean recombobulated = custom != null && custom.copyTag().getInt("rarity_upgrades").orElse(0) > 0;
        return VacuumProfile.parse(stack.getHoverName().getString(),
                lore == null ? List.of() : lore.lines().stream().map(Component::getString).toList(), recombobulated);
    }
}
