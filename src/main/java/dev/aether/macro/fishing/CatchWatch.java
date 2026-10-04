package dev.aether.macro.fishing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

// what the float and the stands around it give away: the bite, where the float came down, what the reel pulled up
final class CatchWatch {

    private static final double MARKER_SEARCH_SIZE = 6.0;
    private static final double TARGET_SEARCH_RADIUS = 16.0;

    private CatchWatch() {
    }

    static boolean hasLiveHook(Minecraft mc) {
        FishingHook hook = mc.player.fishing;
        return hook != null && !hook.isRemoved();
    }

    static boolean hasCatchMarker(Level level, FishingHook hook) {
        AABB box = AABB.ofSize(hook.position(),
                MARKER_SEARCH_SIZE, MARKER_SEARCH_SIZE, MARKER_SEARCH_SIZE);
        for (ArmorStand marker : level.getEntitiesOfClass(ArmorStand.class, box)) {
            if (marker.isRemoved() || marker.getCustomName() == null) {
                continue;
            }
            if (isCatchMarker(stripFormatting(marker.getCustomName().getString()))) {
                return true;
            }
        }
        return false;
    }

    static boolean floatInLiquid(Level level, FishingHook hook, Predicate<BlockState> liquid) {
        BlockPos at = BlockPos.containing(hook.position());
        return liquid.test(level.getBlockState(at)) || liquid.test(level.getBlockState(at.below()));
    }

    // the bite marker shows a single ? and flips to !! once the catch is on the line
    static boolean isCatchMarker(String plainName) {
        return plainName != null && plainName.contains("!!");
    }

    static boolean isBiteMarker(String plainName) {
        return plainName != null && plainName.contains("?");
    }

    static void snapshot(ClientLevel level, Set<Integer> ids) {
        ids.clear();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LivingEntity && !(entity instanceof ArmorStand)) {
                ids.add(entity.getId());
            }
        }
    }

    // a catch is only whatever the reel pulled up, so drops and mobs that were already swimming are left alone
    static boolean shouldAcceptTarget(int entityId, Set<Integer> preReelEntityIds) {
        return !preReelEntityIds.contains(entityId);
    }

    static Entity findTarget(Minecraft mc, Set<Integer> preReelEntityIds, Predicate<Entity> wanted) {
        AABB box = AABB.ofSize(mc.player.position(),
                TARGET_SEARCH_RADIUS * 2, TARGET_SEARCH_RADIUS, TARGET_SEARCH_RADIUS * 2);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : mc.level.getEntities(mc.player, box)) {
            if (!(entity instanceof LivingEntity) || entity instanceof ArmorStand || !isAlive(entity)) {
                continue;
            }
            if (!shouldAcceptTarget(entity.getId(), preReelEntityIds)) {
                continue;
            }
            if (!wanted.test(entity)) {
                continue;
            }
            double distance = entity.distanceToSqr(mc.player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    // sea creatures carry their name on a separate plate, so a miss on the mob still has to check above it
    static boolean matchesName(Level level, Entity entity, String needle) {
        if (stripFormatting(entity.getDisplayName().getString()).toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        AABB box = AABB.ofSize(entity.position().add(0.0, 1.0, 0.0), 3.0, 4.0, 3.0);
        for (ArmorStand marker : level.getEntitiesOfClass(ArmorStand.class, box)) {
            if (marker.getCustomName() != null
                    && stripFormatting(marker.getCustomName().getString())
                            .toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static boolean isAlive(Entity entity) {
        return entity != null
                && !entity.isRemoved()
                && !(entity instanceof LivingEntity living && living.isDeadOrDying());
    }

    static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-or]", "").trim();
    }
}
