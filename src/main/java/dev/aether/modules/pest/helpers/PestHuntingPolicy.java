package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Selects whether a pest is caught with a lasso or killed with the vacuum. */
final class PestHuntingPolicy {
    static final List<String> PEST_TYPES = List.of(
            "Fly", "Cricket", "Locust", "Rat", "Mosquito", "Earthworm",
            "Mite", "Moth", "Slug", "Beetle", "Firefly", "Dragonfly", "Praying Mantis");

    private static final double NAME_MARKER_SEARCH_SIZE = 5.0;

    private PestHuntingPolicy() {
    }

    static boolean shouldLasso(Minecraft client, Entity target) {
        if (!AetherConfig.PEST_HUNTING.get()) {
            return false;
        }
        int typeIndex = findPestTypeIndex(client, target);
        return typeIndex < 0 || (AetherConfig.PEST_HUNTING_VACUUM_PEST_MASK.get() & (1 << typeIndex)) == 0;
    }

    /**
     * Resolves the type from the pest's nameplate rather than its mob class: several
     * Hypixel pests share the same underlying Bat or Silverfish entity.
     */
    static int findPestTypeIndex(Minecraft client, Entity target) {
        if (target == null) {
            return -1;
        }
        int direct = findPestTypeIndex(target.getDisplayName().getString());
        if (direct >= 0 || client == null || client.level == null) {
            return direct;
        }

        AABB searchBox = AABB.ofSize(target.position().add(0.0, 1.5, 0.0),
                NAME_MARKER_SEARCH_SIZE, NAME_MARKER_SEARCH_SIZE, NAME_MARKER_SEARCH_SIZE);
        int nearestType = -1;
        double nearestDistance = Double.MAX_VALUE;
        for (ArmorStand marker : client.level.getEntitiesOfClass(ArmorStand.class, searchBox)) {
            Component name = marker.getCustomName();
            if (name == null || !isNearTarget(marker, target)) {
                continue;
            }
            int markerType = findPestTypeIndex(name.getString());
            double distance = marker.distanceToSqr(target);
            if (markerType >= 0 && distance < nearestDistance) {
                nearestType = markerType;
                nearestDistance = distance;
            }
        }
        return nearestType;
    }

    static int findPestTypeIndex(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String plain = PestHuntingController.stripFormatting(text).toLowerCase(Locale.ROOT);
        for (int i = 0; i < PEST_TYPES.size(); i++) {
            String type = PEST_TYPES.get(i).toLowerCase(Locale.ROOT);
            if (Pattern.compile("(?<![a-z])" + Pattern.quote(type) + "(?![a-z])").matcher(plain).find()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isNearTarget(ArmorStand marker, Entity target) {
        double dx = marker.getX() - target.getX();
        double dz = marker.getZ() - target.getZ();
        double dy = marker.getY() - target.getY();
        return dx * dx + dz * dz <= 1.5625 && dy >= -1.0 && dy <= 5.0;
    }
}
