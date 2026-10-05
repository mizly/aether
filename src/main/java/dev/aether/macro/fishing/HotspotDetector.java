package dev.aether.macro.fishing;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

// hypixel marks every fishing hotspot with an invisible stand named HOTSPOT floating over its middle
final class HotspotDetector {

    static final double SELECT_RANGE = 48.0;
    // past this the stand is about to leave entity range, so the hotspot is as good as gone
    static final double GONE_RANGE = 64.0;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int SURFACE_DEPTH = 4;
    private static final String HOTSPOT_NAME = "HOTSPOT";

    // centre is the stand's x/z at the height of the liquid's surface under it
    record Hotspot(int id, Vec3 centre, BlockPos surfaceCell, boolean lava) {

        // inside the top liquid cell, which is the cell a cast search aims into
        Vec3 aimPoint() {
            return new Vec3(centre.x, surfaceCell.getY() + 0.5, centre.z);
        }

        Predicate<BlockState> liquid() {
            return lava ? CastSim::isLava : CastSim::isWater;
        }
    }

    private final List<Hotspot> seen = new ArrayList<>();
    private int ticksUntilScan;

    void clear() {
        seen.clear();
        ticksUntilScan = 0;
    }

    void tick(Minecraft mc) {
        if (ticksUntilScan-- > 0) {
            return;
        }
        ticksUntilScan = SCAN_INTERVAL_TICKS - 1;
        seen.clear();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || stand.isRemoved() || stand.getCustomName() == null
                    || !isHotspotName(stand.getCustomName().getString())) {
                continue;
            }
            Hotspot hotspot = surfaceUnder(mc.level, stand.getId(), stand.position());
            if (hotspot != null) {
                seen.add(hotspot);
            }
        }
    }

    List<Hotspot> seen() {
        return seen;
    }

    boolean isGone(Minecraft mc, Hotspot hotspot) {
        Entity stand = mc.level.getEntity(hotspot.id());
        return gone(stand == null || stand.isRemoved(), mc.player.position().distanceTo(hotspot.centre()));
    }

    static boolean isHotspotName(String name) {
        return HOTSPOT_NAME.equals(CatchWatch.stripFormatting(name));
    }

    static boolean gone(boolean standMissing, double distance) {
        return standMissing || distance > GONE_RANGE;
    }

    // the stand floats a little over the liquid, so the first fluid a few blocks down is the surface it marks
    static Hotspot surfaceUnder(BlockGetter level, int id, Vec3 stand) {
        BlockPos top = BlockPos.containing(stand);
        for (int drop = 0; drop <= SURFACE_DEPTH; drop++) {
            BlockPos pos = top.below(drop);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.isEmpty()) {
                continue;
            }
            double surface = pos.getY() + fluid.getHeight(level, pos);
            return new Hotspot(id, new Vec3(stand.x, surface, stand.z), pos, fluid.getType().isSame(Fluids.LAVA));
        }
        return null;
    }

    static Hotspot nearest(List<Hotspot> hotspots, Vec3 from, Collection<Integer> skipped) {
        Hotspot best = null;
        double bestDistance = SELECT_RANGE;
        for (Hotspot hotspot : hotspots) {
            double distance = from.distanceTo(hotspot.centre());
            if (distance <= bestDistance && !skipped.contains(hotspot.id())
                    && (best == null || distance < bestDistance)) {
                best = hotspot;
                bestDistance = distance;
            }
        }
        return best;
    }
}
