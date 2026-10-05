package dev.aether.macro.fishing;

import dev.aether.util.EntityUtils;
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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

// what the float and the stands around it give away: the bite, where the float came down, what the reel pulled up
final class CatchWatch {

    private static final double MARKER_SEARCH_SIZE = 6.0;
    private static final double TARGET_SEARCH_RADIUS = 16.0;
    // hypixel spawns a mob's plate stand right after the mob, give or take whatever else spawned in between
    private static final int PLATE_ID_SPAN = 3;
    private static final double PLATE_RADIUS = 0.6;
    private static final double PLATE_ABOVE = 3.0;
    // where a plate can sit at all; tall enough to also catch the hotspot stand over a buff line
    private static final double PLATE_SEARCH_RADIUS = 1.5;
    private static final double PLATE_SEARCH_BELOW = 2.0;
    private static final double PLATE_SEARCH_ABOVE = 5.0;
    private static final String HOTSPOT_NAME = "HOTSPOT";
    // the buff line hangs at the hotspot stand's own x/z, at most a block under it
    private static final double HOTSPOT_BUFF_DROP = 1.0;
    private static final double SAME_SPOT = 0.05;
    private static final Pattern HOOK_TIMER = Pattern.compile("\\d+(?:\\.\\d+)?");
    // a 170 tick throw is about the longest flight the cast sim plans for
    private static final int SETTLE_FALLBACK_TICKS = 170;
    private static final long SETTLE_GRACE_MS = 1_500L;
    // the float's own box is a quarter block tall, so it bobs a little over the surface it rests on
    private static final double FLOAT_SURFACE_SLACK = 0.25;
    // our own timer floats right over our float; a neighbour's sits over theirs, at least a block off
    private static final double MARKER_LOCK_RADIUS = 1.5;
    private static final double MARKER_LOCK_BELOW = 1.0;
    private static final double MARKER_LOCK_ABOVE = 3.0;
    static final double CATCH_RADIUS = 4.0;

    private CatchWatch() {
    }

    record Stand(int id, Vec3 pos, String name) {
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

    static long settleDeadlineMs(int predictedTicks) {
        return (predictedTicks > 0 ? predictedTicks : SETTLE_FALLBACK_TICKS) * 50L + SETTLE_GRACE_MS;
    }

    // where the float came down only means something once it has stopped flying
    static boolean settled(Level level, FishingHook hook, Predicate<BlockState> liquid, long now, long hookSeenAt,
                           long deadlineMs) {
        return settled(hook.getHookedIn() != null, hook.onGround(), floatsOn(level, hook, liquid),
                now, hookSeenAt, deadlineMs);
    }

    static boolean settled(boolean hookedIn, boolean onGround, boolean inLiquid, long now, long hookSeenAt,
                           long deadlineMs) {
        return hookedIn || onGround || inLiquid || now - hookSeenAt >= deadlineMs;
    }

    static boolean floatsOn(Level level, FishingHook hook, Predicate<BlockState> liquid) {
        BlockPos at = BlockPos.containing(hook.position());
        return floatsOn(level, at, hook.getY(), liquid) || floatsOn(level, at.below(), hook.getY(), liquid);
    }

    private static boolean floatsOn(Level level, BlockPos pos, double y, Predicate<BlockState> liquid) {
        BlockState state = level.getBlockState(pos);
        return liquid.test(state)
                && withinSurface(y, pos.getY() + state.getFluidState().getHeight(level, pos));
    }

    static boolean withinSurface(double floatY, double surfaceY) {
        return floatY <= surfaceY + FLOAT_SURFACE_SLACK;
    }

    // at a crowded spot every float has a timer, so only the one nearest ours is read for the bite
    static int lockMarker(Level level, FishingHook hook) {
        Vec3 at = hook.position();
        AABB box = new AABB(at.x - MARKER_LOCK_RADIUS, at.y - MARKER_LOCK_BELOW, at.z - MARKER_LOCK_RADIUS,
                at.x + MARKER_LOCK_RADIUS, at.y + MARKER_LOCK_ABOVE, at.z + MARKER_LOCK_RADIUS);
        List<Stand> stands = new ArrayList<>();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, box)) {
            if (!stand.isRemoved() && stand.getCustomName() != null) {
                stands.add(new Stand(stand.getId(), stand.position(),
                        stripFormatting(stand.getCustomName().getString())));
            }
        }
        return nearestMarker(at, stands);
    }

    static int nearestMarker(Vec3 hook, List<Stand> stands) {
        Stand best = null;
        for (Stand stand : stands) {
            if (!isMarkerName(stand.name())
                    || horizontalSq(hook, stand.pos()) > MARKER_LOCK_RADIUS * MARKER_LOCK_RADIUS) {
                continue;
            }
            if (best == null || nearer(hook, stand, best)) {
                best = stand;
            }
        }
        return best == null ? -1 : best.id();
    }

    // the waiting ? is the same stand that later counts down and flips to !!
    static boolean isMarkerName(String name) {
        return isHookTimer(name) || isCatchMarker(name) || "?".equals(name);
    }

    static boolean isBite(Level level, int lockedId) {
        if (lockedId < 0) {
            return false;
        }
        Entity entity = level.getEntity(lockedId);
        return entity instanceof ArmorStand stand && !stand.isRemoved() && stand.getCustomName() != null
                && isCatchMarker(stripFormatting(stand.getCustomName().getString()));
    }

    // a catch surfaces at the float, so anything new farther out belongs to somebody else's line
    static List<Entity> newCatches(Level level, Vec3 hookPos, double radius, Set<Integer> preReelIds) {
        Minecraft mc = Minecraft.getInstance();
        List<Entity> found = new ArrayList<>();
        for (Entity entity : level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(hookPos, radius * 2, radius * 2, radius * 2))) {
            if (entity instanceof ArmorStand || !isAlive(entity) || EntityUtils.isRealPlayer(mc, entity)) {
                continue;
            }
            if (isNewCatch(entity.getId(), entity.position(), hookPos, radius, preReelIds)) {
                found.add(entity);
            }
        }
        found.sort(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(hookPos)));
        return found;
    }

    static boolean isNewCatch(int id, Vec3 pos, Vec3 hookPos, double radius, Set<Integer> preReelIds) {
        return !preReelIds.contains(id) && pos.distanceToSqr(hookPos) <= radius * radius;
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
            if (!(entity instanceof LivingEntity) || entity instanceof ArmorStand || !isAlive(entity)
                    || EntityUtils.isRealPlayer(mc, entity)) {
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

    // the vanilla type name would match every sea creature built on the same mob, so only the plate counts
    static boolean matchesName(Level level, Entity entity, String needle) {
        String plate = plateName(level, entity);
        return plate != null && plate.toLowerCase(Locale.ROOT).contains(needle);
    }

    // sea creatures carry their name on a stand of their own; null until that stand has shown up
    static String plateName(Level level, Entity mob) {
        AABB box = new AABB(mob.getX() - PLATE_SEARCH_RADIUS, mob.getY() - PLATE_SEARCH_BELOW,
                mob.getZ() - PLATE_SEARCH_RADIUS, mob.getX() + PLATE_SEARCH_RADIUS,
                mob.getY() + PLATE_SEARCH_ABOVE, mob.getZ() + PLATE_SEARCH_RADIUS);
        List<Stand> stands = new ArrayList<>();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, box)) {
            if (!stand.isRemoved() && stand.getCustomName() != null) {
                stands.add(new Stand(stand.getId(), stand.position(),
                        stripFormatting(stand.getCustomName().getString())));
            }
        }
        if (stands.isEmpty()) {
            return null;
        }
        List<Vec3> others = new ArrayList<>();
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class,
                box.inflate(PLATE_RADIUS, PLATE_ABOVE, PLATE_RADIUS))) {
            if (other != mob && !(other instanceof ArmorStand)) {
                others.add(other.position());
            }
        }
        Stand plate = pickPlate(mob.getId(), mob.position(), stands, others);
        return plate == null ? null : plate.name();
    }

    // a stand a few ids on is the surest match, then the closest one floating over the mob
    static Stand pickPlate(int mobId, Vec3 mob, List<Stand> stands, List<Vec3> others) {
        for (int step = 1; step <= PLATE_ID_SPAN; step++) {
            for (Stand stand : stands) {
                if (stand.id() == mobId + step && isPlate(stand, stands) && ownedBy(stand.pos(), mob, others)) {
                    return stand;
                }
            }
        }
        Stand best = null;
        for (Stand stand : stands) {
            if (platesOver(mob, stand.pos()) && isPlate(stand, stands) && ownedBy(stand.pos(), mob, others)
                    && (best == null || nearer(mob, stand, best))) {
                best = stand;
            }
        }
        return best;
    }

    static boolean isPlateName(String name) {
        return name != null && !name.isBlank() && !name.equals(HOTSPOT_NAME)
                && !isHookTimer(name) && !isCatchMarker(name);
    }

    // the stand over a float counts down the seconds until the catch arrives
    static boolean isHookTimer(String name) {
        return name != null && HOOK_TIMER.matcher(name).matches();
    }

    static boolean sitsUnderHotspot(Vec3 hotspot, Vec3 stand) {
        double drop = hotspot.y - stand.y;
        return Math.abs(hotspot.x - stand.x) <= SAME_SPOT && Math.abs(hotspot.z - stand.z) <= SAME_SPOT
                && drop > 0.0 && drop <= HOTSPOT_BUFF_DROP;
    }

    static boolean platesOver(Vec3 mob, Vec3 stand) {
        double rise = stand.y - mob.y;
        return horizontalSq(mob, stand) <= PLATE_RADIUS * PLATE_RADIUS && rise >= 0.0 && rise <= PLATE_ABOVE;
    }

    // a plate floats right over its own mob, so a stand some other mob sits closer under is that mob's
    static boolean ownedBy(Vec3 stand, Vec3 mob, List<Vec3> others) {
        double mine = horizontalSq(mob, stand);
        for (Vec3 other : others) {
            if (platesOver(other, stand) && horizontalSq(other, stand) < mine) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPlate(Stand stand, List<Stand> stands) {
        if (!isPlateName(stand.name())) {
            return false;
        }
        for (Stand other : stands) {
            if (other.name().equals(HOTSPOT_NAME) && sitsUnderHotspot(other.pos(), stand.pos())) {
                return false;
            }
        }
        return true;
    }

    private static boolean nearer(Vec3 mob, Stand stand, Stand best) {
        double across = horizontalSq(mob, stand.pos());
        double bestAcross = horizontalSq(mob, best.pos());
        if (across != bestAcross) {
            return across < bestAcross;
        }
        double up = Math.abs(stand.pos().y - mob.y);
        double bestUp = Math.abs(best.pos().y - mob.y);
        return up != bestUp ? up < bestUp : stand.id() < best.id();
    }

    private static double horizontalSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
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
