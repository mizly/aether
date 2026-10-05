package dev.aether.macro.fishing;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

// hypixel marks every fishing hotspot with an invisible stand named HOTSPOT floating over its middle
final class HotspotDetector {

    static final double SELECT_RANGE = 48.0;
    // past this the stand is about to leave entity range, so the hotspot is as good as gone
    static final double GONE_RANGE = 64.0;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int SURFACE_DEPTH = 4;
    // how far under the surface the floor below a hotspot is looked for
    private static final int FLOOR_DEPTH = 8;
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

    interface Column {
        boolean water(BlockPos pos);

        boolean solid(BlockPos pos);
    }

    // the water floor right under the nametag, or a column beside it, with water over the feet and the head
    // so a player standing there stays under; null when the water is too shallow for that
    static BlockPos centreFeet(Column column, Hotspot hotspot) {
        if (hotspot.lava()) {
            return null;
        }
        BlockPos surface = hotspot.surfaceCell();
        List<BlockPos> columns = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                columns.add(BlockPos.containing(hotspot.centre().x + dx, surface.getY(), hotspot.centre().z + dz));
            }
        }
        columns.sort(Comparator.comparingDouble(cell -> Math.hypot(cell.getX() + 0.5 - hotspot.centre().x,
                cell.getZ() + 0.5 - hotspot.centre().z)));
        for (BlockPos top : columns) {
            BlockPos feet = floorFeet(column, top);
            if (feet != null) {
                return feet;
            }
        }
        return null;
    }

    private static BlockPos floorFeet(Column column, BlockPos top) {
        if (!column.water(top)) {
            return null;
        }
        for (int drop = 1; drop <= FLOOR_DEPTH; drop++) {
            BlockPos floor = top.below(drop);
            if (!column.solid(floor)) {
                if (!column.water(floor)) {
                    return null;
                }
                continue;
            }
            BlockPos feet = floor.above();
            return feet.getY() + 1 <= top.getY() && column.water(feet) && column.water(feet.above()) ? feet : null;
        }
        return null;
    }

    static Column liveColumn(BlockGetter level) {
        return new Column() {
            @Override
            public boolean water(BlockPos pos) {
                return level.getFluidState(pos).is(FluidTags.WATER)
                        && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
            }

            @Override
            public boolean solid(BlockPos pos) {
                return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
            }
        };
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
