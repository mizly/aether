package dev.aether.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Utility methods for finding and interacting with entities in the world.
 */
public final class EntityUtils {

    private EntityUtils() {}

    /**
     * Finds the closest entity whose visible name matches the given substring.
     * Prefers exact matches, then partial matches, and resolves NPC nameplate
     * armor stands back to the nearby character when possible.
     *
     * @param client The Minecraft instance.
     * @param nameSubstring Substring to match against visible entity names.
     * @return The closest matching entity, or {@code null} if none found.
     */
    public static Entity findEntity(Minecraft client, String nameSubstring) {
        if (client.level == null || client.player == null) {
            return null;
        }

        String target = TablistUtils.stripColors(nameSubstring).toLowerCase().trim();
        if (target.isEmpty()) {
            return null;
        }

        Entity closest = null;
        double closestDist = Double.MAX_VALUE;
        int bestMatchScore = -1;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) {
                continue;
            }

            int matchScore = getEntityMatchScore(entity, target);
            if (matchScore < 0) {
                continue;
            }

            double dist = entity.distanceToSqr(client.player);
            if (matchScore > bestMatchScore) {
                bestMatchScore = matchScore;
                closestDist = dist;
                closest = entity;
                continue;
            }

            if (matchScore != bestMatchScore) {
                continue;
            }

            if (!(entity instanceof ArmorStand) && closest instanceof ArmorStand) {
                closestDist = dist;
                closest = entity;
                continue;
            }

            if (dist < closestDist) {
                closestDist = dist;
                closest = entity;
            }
        }

        if (closest instanceof ArmorStand) {
            Entity character = findCharacterNearArmorStand(client, closest);
            if (character != null) {
                return character;
            }
        }

        return closest;
    }

    public static List<String> describeNearbyEntities(Minecraft client, double radius, int maxResults) {
        if (client.level == null || client.player == null) {
            return List.of();
        }

        double radiusSq = radius * radius;
        List<Entity> nearby = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) {
                continue;
            }

            if (entity.distanceToSqr(client.player) <= radiusSq) {
                nearby.add(entity);
            }
        }

        nearby.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(client.player)));

        List<String> lines = new ArrayList<>();
        int count = Math.min(maxResults, nearby.size());
        for (int i = 0; i < count; i++) {
            Entity entity = nearby.get(i);
            double distance = Math.sqrt(entity.distanceToSqr(client.player));
            String name = cleanComponent(entity.getName());
            String displayName = cleanComponent(entity.getDisplayName());
            String customName = cleanComponent(entity.getCustomName());
            String type = entity.getType().toShortString();

            lines.add(String.format(
                    "%s d=%.1f type=%s name=\"%s\" display=\"%s\" custom=\"%s\"",
                    entity.getClass().getSimpleName(),
                    distance,
                    type,
                    name,
                    displayName,
                    customName));
        }

        return lines;
    }

    private static int getEntityMatchScore(Entity entity, String target) {
        int score = scoreName(entity.getName(), target);
        score = Math.max(score, scoreName(entity.getDisplayName(), target));
        score = Math.max(score, scoreName(entity.getCustomName(), target));
        return score;
    }

    private static int scoreName(Component component, String target) {
        if (component == null) {
            return -1;
        }

        String clean = TablistUtils.stripColors(component.getString()).toLowerCase().trim();
        if (clean.isEmpty()) {
            return -1;
        }

        if (clean.equals(target)) {
            return 2;
        }

        if (clean.contains(target) || target.contains(clean)) {
            return 1;
        }

        return -1;
    }

    private static String cleanComponent(Component component) {
        if (component == null) {
            return "";
        }

        return TablistUtils.stripColors(component.getString()).replace('\u00A0', ' ').trim();
    }

    private static Entity findCharacterNearArmorStand(Minecraft client, Entity armorStand) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity instanceof ArmorStand) {
                continue;
            }

            if (entity.distanceToSqr(armorStand) < 4.0) {
                return entity;
            }
        }

        return null;
    }
}
