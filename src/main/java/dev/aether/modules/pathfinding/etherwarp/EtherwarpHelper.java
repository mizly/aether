package dev.aether.modules.pathfinding.etherwarp;

import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public final class EtherwarpHelper {

    public static final double MAX_ETHERWARP_DISTANCE = 60.0;
    public static final double MAX_ETHERWARP_DISTANCE_SQ = MAX_ETHERWARP_DISTANCE * MAX_ETHERWARP_DISTANCE;
    public static final double MODERN_SNEAKING_EYE_HEIGHT = 1.27;
    public static final double LEGACY_SNEAKING_EYE_HEIGHT = 1.54;
    public static final double MODERN_SNEAKING_HEIGHT = 1.8;
    public static final double LEGACY_SNEAKING_HEIGHT = 2.0;

    private static final double PLAYER_COLLISION_MIN = 0.2;
    private static final double PLAYER_COLLISION_MAX = 0.8;

    private static final double[][] CENTER_POINT_OFFSETS = {
            {0.50, 0.50, 0.50},
            {0.50, 0.98, 0.50},
            {0.50, 0.02, 0.50},
            {0.05, 0.50, 0.50},
            {0.95, 0.50, 0.50},
            {0.50, 0.50, 0.05},
            {0.50, 0.50, 0.95}
    };
    private static final List<Vec3> TARGET_POINT_OFFSETS = createTargetPointOffsets();

    private EtherwarpHelper() {
    }

    public static PathPosition resolveTargetFeet(WalkabilityChecker checker, int x, int y, int z) {
        if (checker == null) {
            return null;
        }

        PathPosition topLanding = new PathPosition(x, y + 1, z);
        if (checker.hasWalkableTop(x, y, z) && isValidLandingFeet(checker, topLanding)) {
            return topLanding;
        }

        PathPosition directLanding = new PathPosition(x, y, z);
        if (isValidLandingFeet(checker, directLanding)) {
            return directLanding;
        }

        return null;
    }

    public static boolean isValidLandingFeet(WalkabilityChecker checker, PathPosition feet) {
        if (checker == null || feet == null) {
            return false;
        }

        int x = feet.flooredX();
        int y = feet.flooredY();
        int z = feet.flooredZ();
        double sneakingHeight = getSneakingHeight(Minecraft.getInstance());
        return checker.hasWalkableTop(x, y - 1, z)
                && hasCollisionFreeSpace(checker, x, y, z, sneakingHeight)
                && hasSafeSpace(checker, x, y, z, sneakingHeight);
    }

    public static BlockPos getTargetBlock(PathPosition feet) {
        return new BlockPos(feet.flooredX(), feet.flooredY() - 1, feet.flooredZ());
    }

    public static Vec3 getCenteredFeet(PathPosition feet) {
        return new Vec3(feet.flooredX() + 0.5, feet.flooredY(), feet.flooredZ() + 0.5);
    }

    public static Vec3 getEyePosition(Minecraft mc, PathPosition feet) {
        return feet == null ? null : getEyePosition(mc, getCenteredFeet(feet));
    }

    public static Vec3 getEyePosition(Minecraft mc, Vec3 feetPos) {
        if (feetPos == null) {
            return null;
        }
        return feetPos.add(0.0, getSneakingEyeHeight(mc), 0.0);
    }

    public static boolean canEtherwarp(Minecraft mc, WalkabilityChecker checker, PathPosition fromFeet, PathPosition toFeet) {
        return findVisibleTargetPoint(mc, checker, fromFeet, toFeet) != null;
    }

    public static Vec3 findVisibleTargetPoint(Minecraft mc, WalkabilityChecker checker, PathPosition fromFeet, PathPosition toFeet) {
        if (fromFeet == null) {
            return null;
        }

        return findVisibleTargetPoint(mc, checker, getEyePosition(mc, fromFeet), toFeet);
    }

    public static Vec3 findVisibleTargetPoint(Minecraft mc, WalkabilityChecker checker, Vec3 eyePos, PathPosition toFeet) {
        if (mc == null || mc.level == null || eyePos == null || toFeet == null || !isValidLandingFeet(checker, toFeet)) {
            return null;
        }

        return findVisibleTargetPoint(eyePos, getTargetBlock(toFeet), (from, to) -> clip(mc, from, to));
    }

    static Vec3 findVisibleTargetPoint(Vec3 eyePos, BlockPos targetBlock,
                                       BiFunction<Vec3, Vec3, BlockHitResult> raycast) {
        for (Vec3 offset : TARGET_POINT_OFFSETS) {
            Vec3 targetPoint = new Vec3(
                    targetBlock.getX() + offset.x,
                    targetBlock.getY() + offset.y,
                    targetBlock.getZ() + offset.z);
            if (eyePos.distanceToSqr(targetPoint) > MAX_ETHERWARP_DISTANCE_SQ + 1.0) {
                continue;
            }

            BlockHitResult hit = raycast.apply(eyePos, targetPoint);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(targetBlock)) {
                return targetPoint;
            }
        }

        return null;
    }

    public static boolean isLookingAtTarget(Minecraft mc, Vec3 eyePos, PathPosition feet) {
        return mc != null && mc.level != null && mc.player != null
                && isLookingAtTarget(eyePos, mc.player.getViewVector(1.0f), getTargetBlock(feet),
                (from, to) -> clip(mc, from, to));
    }

    static boolean isLookingAtTarget(Vec3 eyePos, Vec3 direction, BlockPos targetBlock,
                                      BiFunction<Vec3, Vec3, BlockHitResult> raycast) {
        BlockHitResult hit = raycast.apply(eyePos, eyePos.add(direction.scale(MAX_ETHERWARP_DISTANCE)));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(targetBlock);
    }

    private static BlockHitResult clip(Minecraft mc, Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    }

    private static List<Vec3> createTargetPointOffsets() {
        List<Vec3> offsets = new ArrayList<>();
        for (double[] offset : CENTER_POINT_OFFSETS) {
            offsets.add(new Vec3(offset[0], offset[1], offset[2]));
        }
        double[] coordinates = {0.5, 0.05, 0.95};
        for (double x : coordinates) {
            for (double y : coordinates) {
                for (double z : coordinates) {
                    int edges = (x == 0.5 ? 0 : 1) + (y == 0.5 ? 0 : 1) + (z == 0.5 ? 0 : 1);
                    if (edges >= 2) {
                        offsets.add(new Vec3(x, y, z));
                    }
                }
            }
        }
        return List.copyOf(offsets);
    }

    private static boolean hasCollisionFreeSpace(WalkabilityChecker checker, int x, int feetY, int z, double height) {
        double remainingHeight = height;
        int currentY = feetY;
        while (remainingHeight > 0.0) {
            double occupiedHeight = Math.min(1.0, remainingHeight);
            if (intersectsPlayerSpace(checker, x, currentY, z, occupiedHeight)) {
                return false;
            }
            remainingHeight -= occupiedHeight;
            currentY++;
        }
        return true;
    }

    private static boolean hasSafeSpace(WalkabilityChecker checker, int x, int feetY, int z, double height) {
        double remainingHeight = height;
        int currentY = feetY;
        while (remainingHeight > 0.0) {
            if (checker.isDangerous(x, currentY, z)) {
                return false;
            }
            remainingHeight -= Math.min(1.0, remainingHeight);
            currentY++;
        }
        return true;
    }

    private static boolean intersectsPlayerSpace(WalkabilityChecker checker, int x, int y, int z, double occupiedHeight) {
        if (occupiedHeight <= 0.0) {
            return false;
        }

        VoxelShape shape = checker.getState(x, y, z).getCollisionShape(checker.getLevel(), new BlockPos(x, y, z));
        if (shape.isEmpty()) {
            return false;
        }

        for (AABB box : shape.toAabbs()) {
            if (box.maxX > PLAYER_COLLISION_MIN
                    && box.minX < PLAYER_COLLISION_MAX
                    && box.maxZ > PLAYER_COLLISION_MIN
                    && box.minZ < PLAYER_COLLISION_MAX
                    && box.maxY > 0.0
                    && box.minY < occupiedHeight) {
                return true;
            }
        }
        return false;
    }

    private static double getSneakingEyeHeight(Minecraft mc) {
        return usesModernSneakingEyeHeight(mc) ? MODERN_SNEAKING_EYE_HEIGHT : LEGACY_SNEAKING_EYE_HEIGHT;
    }

    private static double getSneakingHeight(Minecraft mc) {
        return usesModernSneakingEyeHeight(mc) ? MODERN_SNEAKING_HEIGHT : LEGACY_SNEAKING_HEIGHT;
    }

    private static boolean usesModernSneakingEyeHeight(Minecraft mc) {
        String areaLine = mc == null ? null : TablistUtils.findLine(mc, "Area:");
        if (areaLine == null) {
            return false;
        }

        String areaName = areaLine;
        int separatorIndex = areaLine.indexOf(':');
        if (separatorIndex >= 0 && separatorIndex + 1 < areaLine.length()) {
            areaName = areaLine.substring(separatorIndex + 1).trim();
        }

        return areaName.equalsIgnoreCase("Galatea")
                || areaName.equalsIgnoreCase("The Park")
                || areaName.equalsIgnoreCase("Hub")
                || areaName.equalsIgnoreCase("Spider Den")
                || areaName.equalsIgnoreCase("Spider's Den");
    }
}
