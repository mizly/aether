package dev.aether.modules.pathfinding.pathing.processing.impl;

import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.pathing.processing.Cost;
import dev.aether.modules.pathfinding.pathing.processing.NodeProcessor;
import dev.aether.modules.pathfinding.pathing.processing.context.EvaluationContext;
import dev.aether.modules.pathfinding.provider.NavigationPoint;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.CollisionContext;

/*
 * most logic in this file is derived from minecraft code
 * or writeups on pathfinding algorithms, if you want to help contribute
 * id prefer for you to keep it the same idea or whatever, but if not
 * please write a comment explaining WHY you did it that way. i dont like
 * magic numbers that i cant understand.
 */
public final class MinecraftPathProcessor implements NodeProcessor {

    private static final double DEFAULT_MOB_JUMP_HEIGHT = 1.125; // WalkNodeEvaluator
    private static final double ASCENT_DY_THRESHOLD = 0.5;
    private static final double PARTIAL_ASCENT_DY_THRESHOLD = 0.2;
    private static final double DESCENT_DY_THRESHOLD = -0.1;
    private static final double FULL_STEP_ASCENT_DY_SCALE = 0.5;
    private static final double FULL_STEP_ASCENT_BASE_PENALTY = 2.0;
    private static final double DESCENT_COST_SCALE = 0.1;

    private static final double CARDINAL_WALL_COST = 0.55;
    private static final double DIAGONAL_WALL_COST = 0.25;

    // Partial ascents (slabs/stairs) should prefer centered entry/lines over edge hugging.
    private static final double PARTIAL_ASCENT_CARDINAL_EDGE_PENALTY = 0.32;
    private static final double PARTIAL_ASCENT_DIAGONAL_EDGE_PENALTY = 0.12;
    private static final double PARTIAL_ASCENT_ENTRY_SIDE_PENALTY = 0.28;

    // Openings between spaces should be entered centered; penalize side-biased approach lines.
    private static final double OPENING_ENTRY_IMBALANCE_PENALTY = 0.24;

    // Approach centering: how far ahead to scan for upcoming transitions
    private static final int APPROACH_LOOKAHEAD_DIST = 3;

    // Multipliers for boosting wall-proximity cost when a transition is ahead.
    // Decay with distance: closer = stronger centering pressure.
    // These multiply the existing CARDINAL_WALL_COST / DIAGONAL_WALL_COST.
    private static final double APPROACH_MULTIPLIER_DIST_1 = 0.85; // +0.47/cardinal wall
    private static final double APPROACH_MULTIPLIER_DIST_2 = 0.55; // +0.30/cardinal wall
    private static final double APPROACH_MULTIPLIER_DIST_3 = 0.30; // +0.17/cardinal wall

    // Lateral imbalance penalty when approaching an opening (applied before reaching it)
    private static final double APPROACH_OPENING_IMBALANCE_PENALTY = 0.18;

    private final Minecraft mc = Minecraft.getInstance();
    private final WalkabilityChecker checker;
    private final double maxJumpHeight;

    // Cache for isInsideSolidSpace(prev) - prev is the same node for all neighbors in one expansion.
    // Avoids 2 redundant topY lookups per neighbor (saves 9 lookups per 10-neighbor expansion).
    private PathPosition lastCheckedPrev = null;
    private boolean lastPrevInsideSolid = false;

    public MinecraftPathProcessor() {
        this(null, DEFAULT_MOB_JUMP_HEIGHT);
    }

    public MinecraftPathProcessor(WalkabilityChecker checker) {
        this(checker, DEFAULT_MOB_JUMP_HEIGHT);
    }

    public MinecraftPathProcessor(WalkabilityChecker checker, double maxJumpHeight) {
        this.checker = checker;
        this.maxJumpHeight = Math.max(DEFAULT_MOB_JUMP_HEIGHT, maxJumpHeight);
    }

    @Override
    public boolean isValid(EvaluationContext context) {
        var provider = context.getNavigationPointProvider();
        var pos      = context.getCurrentPathPosition();
        var prev     = context.getPreviousPathPosition();
        var env      = context.getEnvironmentContext();

        // Cheapest rejection first: check if player space is inside solid geometry
        if (prev != null) {
            // Cache prev's solid-space check - it's the same expanded node for all 10 neighbors
            if (prev != lastCheckedPrev) {
                lastCheckedPrev = prev;
                lastPrevInsideSolid = isInsideSolidSpace(prev);
            }
            if (lastPrevInsideSolid || isInsideSolidSpace(pos)) return false;
        }

        NavigationPoint currentPoint = provider.getNavigationPoint(pos, env);

        if (!currentPoint.isTraversable()) return false;
        if (prev == null) return true;

        Level level = checker != null ? checker.getLevel() : mc.level;
        if (level == null) return false;

        NavigationPoint prevPoint = provider.getNavigationPoint(prev, env);
        double dy = pos.y - prev.y;
        int    dx = pos.flooredX() - prev.flooredX();
        int    dz = pos.flooredZ() - prev.flooredZ();

        if (dy > maxJumpHeight) return false;

        // Diagonal corner check: both intermediate corners must be traversable
        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            PathPosition corner1 = prev.add(dx, 0.0, 0.0);
            PathPosition corner2 = prev.add(0.0, 0.0, dz);
            NavigationPoint c1 = provider.getNavigationPoint(corner1, env);
            NavigationPoint c2 = provider.getNavigationPoint(corner2, env);
            if (!c1.isTraversable() || !c2.isTraversable()) return false;

            // Partial-floor extension: on a slab/stair the player's feet sit at flooredY+0.5,
            // so their bounding box extends into the (flooredY-1) block slice.  A full wall at
            // a corner at that lower slice will physically clip the player even though the
            // standard check (which only looks at flooredY and flooredY+1) passes.
            if (checker != null) {
                int fy = prev.flooredY(); // == pos.flooredY() because dy==0 for diagonals
                double srcFloor = checker.getTopY(prev.flooredX(), fy - 1, prev.flooredZ());
                double dstFloor = checker.getTopY(pos.flooredX(),  fy - 1, pos.flooredZ());
                if ((srcFloor > 0.1 && srcFloor < 0.95) || (dstFloor > 0.1 && dstFloor < 0.95)) {
                    int c1x = corner1.flooredX(), c1z = corner1.flooredZ();
                    int c2x = corner2.flooredX(), c2z = corner2.flooredZ();
                    if (checker.isFullWall(c1x, fy - 1, c1z) || checker.isFullWall(c2x, fy - 1, c2z)) {
                        return false;
                    }
                }
            }
        }

        return switch (Double.compare(dy, 0.0)) {
            case -1 -> dy < -0.5     // falling
                    ? true
                    : currentPoint.hasFloor() || prevPoint.hasFloor()
                   || currentPoint.isClimbable() || prevPoint.isClimbable();
            case  1 -> dy > 0.5      // jumping/climbing
                    ? prevPoint.hasFloor() || currentPoint.isClimbable()
                    : currentPoint.hasFloor() || prevPoint.hasFloor()
                   || currentPoint.isClimbable() || prevPoint.isClimbable();
            default -> currentPoint.hasFloor() || prevPoint.hasFloor()
                    || currentPoint.isClimbable() || prevPoint.isClimbable();
        };
    }

    @Override
    public Cost calculateCostContribution(EvaluationContext context) {
        Level level = checker != null ? checker.getLevel() : mc.level;
        if (level == null) return Cost.ZERO;

        var currentPos = context.getCurrentPathPosition();
        var prevPos    = context.getPreviousPathPosition();
        if (prevPos == null) return Cost.ZERO;

        var provider     = context.getNavigationPointProvider();
        var env          = context.getEnvironmentContext();
        var currentPoint = provider.getNavigationPoint(currentPos, env);
        var prevPoint    = provider.getNavigationPoint(prevPos,    env);

        double dy             = currentPoint.getFloorLevel() - prevPoint.getFloorLevel();
        double additionalCost = 0.0;

        int cx = currentPos.flooredX();
        int cy = currentPos.flooredY();
        int cz = currentPos.flooredZ();
        int px = prevPos.flooredX();
        int py = prevPos.flooredY();
        int pz = prevPos.flooredZ();

        boolean fullStepSupport = isFullStepSupport(cx, cy - 1, cz);

        // Elevation cost: only true full-block ascents get heavy penalties.
        // Stairs/slabs are treated as partial ascents and stay cheap.
        if (dy > ASCENT_DY_THRESHOLD) {
            if (fullStepSupport) {
                additionalCost += FULL_STEP_ASCENT_DY_SCALE * dy + FULL_STEP_ASCENT_BASE_PENALTY;
            }
        } else if (dy < DESCENT_DY_THRESHOLD) {
            additionalCost += DESCENT_COST_SCALE * Math.abs(dy);
        }

        int moveDx = cx - px;
        int moveDz = cz - pz;

        // Cramped space penalty: ceiling proximity
        for (int i = 2; i <= 3; i++) {
            if (canOcclude(cx, cy + i, cz)) {
                additionalCost += 0.1 / i;
            }
        }

        // Wall proximity gradient: single pass over all 8 neighbors to compute
        // cardinal walls, diagonal walls, entry-side walls, lateral imbalance, and side walls.
        boolean wallN = isFullWallBlock(cx, cy, cz - 1);
        boolean wallS = isFullWallBlock(cx, cy, cz + 1);
        boolean wallW = isFullWallBlock(cx - 1, cy, cz);
        boolean wallE = isFullWallBlock(cx + 1, cy, cz);
        boolean wallNW = isFullWallBlock(cx - 1, cy, cz - 1);
        boolean wallNE = isFullWallBlock(cx + 1, cy, cz - 1);
        boolean wallSW = isFullWallBlock(cx - 1, cy, cz + 1);
        boolean wallSE = isFullWallBlock(cx + 1, cy, cz + 1);

        int cardinalWalls = (wallN ? 1 : 0) + (wallS ? 1 : 0) + (wallW ? 1 : 0) + (wallE ? 1 : 0);
        int diagonalWalls = (wallNW ? 1 : 0) + (wallNE ? 1 : 0) + (wallSW ? 1 : 0) + (wallSE ? 1 : 0);

        boolean partialAscent = dy > PARTIAL_ASCENT_DY_THRESHOLD && !fullStepSupport;
        double cardinalWallWeight = CARDINAL_WALL_COST;
        double diagonalWallWeight = DIAGONAL_WALL_COST;
        if (partialAscent) {
            cardinalWallWeight += PARTIAL_ASCENT_CARDINAL_EDGE_PENALTY;
            diagonalWallWeight += PARTIAL_ASCENT_DIAGONAL_EDGE_PENALTY;
        }
        additionalCost += cardinalWalls * cardinalWallWeight + diagonalWalls * diagonalWallWeight;

        // Skip all corridor-centering logic when in open space - this is the
        // common case for large open-field searches and saves ~30 hashmap lookups per node.
        if (cardinalWalls > 0 || diagonalWalls > 0) {
            // Approach centering: boost wall penalties when a stairway or opening is ahead
            int ascentDist  = detectPartialAscentAhead(cx, cy, cz, moveDx, moveDz);
            int openingDist = detectOpeningAhead(cx, cy, cz, moveDx, moveDz);
            int transitionDist = (ascentDist > 0 && openingDist > 0)
                    ? Math.min(ascentDist, openingDist)
                    : Math.max(ascentDist, openingDist); // whichever is nonzero

            if (transitionDist > 0) {
                double multiplier = switch (transitionDist) {
                    case 1  -> APPROACH_MULTIPLIER_DIST_1;
                    case 2  -> APPROACH_MULTIPLIER_DIST_2;
                    default -> APPROACH_MULTIPLIER_DIST_3;
                };
                additionalCost += cardinalWalls * (CARDINAL_WALL_COST * multiplier);
                additionalCost += diagonalWalls * (DIAGONAL_WALL_COST * multiplier);
            }

            // Approach imbalance: penalize lateral asymmetry before an opening
            if (openingDist > 0 && isCardinalMove(moveDx, moveDz)) {
                int imbalance = countLateralImbalance(cx, cy, cz, moveDx, moveDz, wallN, wallS, wallW, wallE);
                double decay = switch (openingDist) {
                    case 1  -> 1.0;
                    case 2  -> 0.65;
                    default -> 0.35;
                };
                additionalCost += imbalance * APPROACH_OPENING_IMBALANCE_PENALTY * decay;
            }

            if (partialAscent) {
                int entrySideWalls = countEntrySideWalls(px, py, pz, moveDx, moveDz);
                entrySideWalls += countEntrySideWalls(cx, cy, cz, moveDx, moveDz);
                additionalCost += entrySideWalls * PARTIAL_ASCENT_ENTRY_SIDE_PENALTY;
            }

            if (isRoomOpeningTransition(px, py, pz, cx, cy, cz, moveDx, moveDz)) {
                int imbalance = countLateralImbalanceAt(px, py, pz, moveDx, moveDz)
                        + countLateralImbalanceAt(cx, cy, cz, moveDx, moveDz);
                additionalCost += imbalance * OPENING_ENTRY_IMBALANCE_PENALTY;
            }
        }

        // Directional penalty: 3-hop weighted average direction vs current direction.
        var gpPos  = context.getGrandparentPathPosition();
        var ggpPos = context.getGreatGrandparentPathPosition();

        // Inline direction computation - no ArrayList or double[] allocations
        double avgX = 0, avgZ = 0, totalW = 0;
        if (gpPos != null) {
            double dx1 = prevPos.x - gpPos.x;
            double dz1 = prevPos.z - gpPos.z;
            double len1 = Math.sqrt(dx1 * dx1 + dz1 * dz1);
            if (len1 > 0.1) {
                avgX += dx1 / len1;
                avgZ += dz1 / len1;
                totalW += 1.0;
            }
        }
        if (ggpPos != null && gpPos != null) {
            double dx2 = gpPos.x - ggpPos.x;
            double dz2 = gpPos.z - ggpPos.z;
            double len2 = Math.sqrt(dx2 * dx2 + dz2 * dz2);
            if (len2 > 0.1) {
                avgX += dx2 / len2 * 0.6;
                avgZ += dz2 / len2 * 0.6;
                totalW += 0.6;
            }
        }
        if (totalW > 0.1) {
            avgX /= totalW;
            avgZ /= totalW;
            double cdx = currentPos.x - prevPos.x;
            double cdz = currentPos.z - prevPos.z;
            double curLen = Math.sqrt(cdx * cdx + cdz * cdz);
            if (curLen > 0.1) {
                double dot = (cdx / curLen) * avgX + (cdz / curLen) * avgZ;
                double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
                if (angleDeg < 5.0) {
                    additionalCost -= 0.3; // straightness bonus
                } else {
                    additionalCost += (angleDeg / 90.0) * 0.8;
                }
            }
        }

        double finalCost = Math.max(0.0, additionalCost);
        return Cost.of(finalCost);
    }

    public static double computeStaticCost(Level level, BlockPos bp) {
        double cost = 0.0;
        for (int i = 2; i <= 3; i++) {
            if (level.getBlockState(bp.above(i)).canOcclude()) cost += 0.1 / i;
        }
        int cardinalWalls = 0;
        if (isFullWallBlockStatic(level, bp.west()))  cardinalWalls++;
        if (isFullWallBlockStatic(level, bp.east()))   cardinalWalls++;
        if (isFullWallBlockStatic(level, bp.north())) cardinalWalls++;
        if (isFullWallBlockStatic(level, bp.south())) cardinalWalls++;
        int diagonalWalls = 0;
        if (isFullWallBlockStatic(level, bp.north().west())) diagonalWalls++;
        if (isFullWallBlockStatic(level, bp.north().east())) diagonalWalls++;
        if (isFullWallBlockStatic(level, bp.south().west())) diagonalWalls++;
        if (isFullWallBlockStatic(level, bp.south().east())) diagonalWalls++;
        cost += cardinalWalls * 0.4 + diagonalWalls * 0.15;
        return cost;
    }

    private static boolean isFullWallBlockStatic(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.canOcclude() && state.isCollisionShapeFullBlock(level, pos);
    }

    // --- Checker-backed query methods (int coords, no BlockPos allocation) --

    private boolean isFullWallBlock(int x, int y, int z) {
        if (checker != null) return checker.isFullWallBlock(x, y, z);
        Level level = mc.level;
        if (level == null) return false;
        BlockPos pos = new BlockPos(x, y, z);
        return isFullWallBlockStatic(level, pos);
    }

    private boolean canOcclude(int x, int y, int z) {
        if (checker != null) return checker.canOcclude(x, y, z);
        Level level = mc.level;
        if (level == null) return false;
        return level.getBlockState(new BlockPos(x, y, z)).canOcclude();
    }

    private boolean isFullStepSupport(int x, int y, int z) {
        if (checker != null) {
            return checker.isFullWall(x, y, z);
        }
        Level level = mc.level;
        if (level == null) return false;
        return isFullWallBlockStatic(level, new BlockPos(x, y, z));
    }

    private boolean isInsideSolidSpace(PathPosition pos) {
        int fx = pos.flooredX();
        int fy = pos.flooredY();
        int fz = pos.flooredZ();
        if (checker != null) {
            // Treat passable partial blocks such as carpet as empty player space.
            return isBlockingPlayerSpace(fx, fy, fz) || isBlockingPlayerSpace(fx, fy + 1, fz);
        }
        Level level = mc.level;
        if (level == null) return true;
        BlockPos feet = new BlockPos(fx, fy, fz);
        BlockPos head = feet.above();
        var empty = CollisionContext.empty();
        return !level.getBlockState(feet).getCollisionShape(level, feet, empty).isEmpty()
                || !level.getBlockState(head).getCollisionShape(level, head, empty).isEmpty();
    }

    private boolean isBlockingPlayerSpace(int x, int y, int z) {
        return !checker.isPassable(x, y, z) && checker.getTopY(x, y, z) > 0.0;
    }

    // --- Wall counting helpers (int coords) --------------------------------

    private int countEntrySideWalls(int x, int y, int z, int moveDx, int moveDz) {
        if (Math.abs(moveDx) > Math.abs(moveDz)) {
            int walls = 0;
            if (isFullWallBlock(x, y, z - 1)) walls++;
            if (isFullWallBlock(x, y, z + 1)) walls++;
            return walls;
        }
        if (Math.abs(moveDz) > Math.abs(moveDx)) {
            int walls = 0;
            if (isFullWallBlock(x - 1, y, z)) walls++;
            if (isFullWallBlock(x + 1, y, z)) walls++;
            return walls;
        }
        // Diagonal/ambiguous entry
        int walls = 0;
        if (isFullWallBlock(x, y, z - 1)) walls++;
        if (isFullWallBlock(x, y, z + 1)) walls++;
        if (isFullWallBlock(x - 1, y, z)) walls++;
        if (isFullWallBlock(x + 1, y, z)) walls++;
        return walls;
    }

    private boolean isRoomOpeningTransition(int px, int py, int pz, int cx, int cy, int cz,
                                             int moveDx, int moveDz) {
        if (!isCardinalMove(moveDx, moveDz)) return false;
        int prevSides = countCardinalSideWalls(px, py, pz, moveDx, moveDz);
        int curSides  = countCardinalSideWalls(cx, cy, cz, moveDx, moveDz);
        return curSides > prevSides && curSides >= 1;
    }

    private static int countLateralImbalance(int x, int y, int z, int moveDx, int moveDz,
                                              boolean wallN, boolean wallS, boolean wallW, boolean wallE) {
        if (!isCardinalMove(moveDx, moveDz)) return 0;
        boolean sideA, sideB;
        if (Math.abs(moveDx) > Math.abs(moveDz)) {
            sideA = wallN;
            sideB = wallS;
        } else {
            sideA = wallW;
            sideB = wallE;
        }
        return sideA ^ sideB ? 1 : 0;
    }

    private int countLateralImbalanceAt(int x, int y, int z, int moveDx, int moveDz) {
        if (!isCardinalMove(moveDx, moveDz)) return 0;
        boolean sideA, sideB;
        if (Math.abs(moveDx) > Math.abs(moveDz)) {
            sideA = isFullWallBlock(x, y, z - 1);
            sideB = isFullWallBlock(x, y, z + 1);
        } else {
            sideA = isFullWallBlock(x - 1, y, z);
            sideB = isFullWallBlock(x + 1, y, z);
        }
        return sideA ^ sideB ? 1 : 0;
    }

    private int countCardinalSideWalls(int x, int y, int z, int moveDx, int moveDz) {
        if (!isCardinalMove(moveDx, moveDz)) return 0;
        if (Math.abs(moveDx) > Math.abs(moveDz)) {
            int walls = 0;
            if (isFullWallBlock(x, y, z - 1)) walls++;
            if (isFullWallBlock(x, y, z + 1)) walls++;
            return walls;
        }
        int walls = 0;
        if (isFullWallBlock(x - 1, y, z)) walls++;
        if (isFullWallBlock(x + 1, y, z)) walls++;
        return walls;
    }

    private static boolean isCardinalMove(int moveDx, int moveDz) {
        return (moveDx == 0) != (moveDz == 0);
    }

    private int detectPartialAscentAhead(int bx, int by, int bz, int moveDx, int moveDz) {
        if (moveDx == 0 && moveDz == 0) return 0;
        int stepX = Integer.signum(moveDx);
        int stepZ = Integer.signum(moveDz);

        for (int dist = 1; dist <= APPROACH_LOOKAHEAD_DIST; dist++) {
            int ax = bx + stepX * dist;
            int az = bz + stepZ * dist;

            // Full wall ahead blocks further scanning
            if (isFullWallBlock(ax, by, az)) break;

            int belowY = by - 1;
            if (checker != null) {
                if (checker.isAir(ax, belowY, az)) continue;
                if (!checker.isFullWallBlock(ax, belowY, az)) {
                    double topY = checker.getTopY(ax, belowY, az);
                    if (topY > 0.2 && topY < 0.95) return dist;
                }
            } else {
                Level level = mc.level;
                if (level == null) return 0;
                BlockPos aheadBelow = new BlockPos(ax, belowY, az);
                var belowState = level.getBlockState(aheadBelow);
                if (belowState.isAir()) continue;
                if (!isFullWallBlockStatic(level, aheadBelow)) {
                    var shape = belowState.getCollisionShape(level, aheadBelow);
                    if (!shape.isEmpty()) {
                        double topY = shape.bounds().maxY;
                        if (topY > 0.2 && topY < 0.95) return dist;
                    }
                }
            }
        }
        return 0;
    }

    private int detectOpeningAhead(int bx, int by, int bz, int moveDx, int moveDz) {
        if (!isCardinalMove(moveDx, moveDz)) return 0;
        int stepX = Integer.signum(moveDx);
        int stepZ = Integer.signum(moveDz);

        int prevSides = countCardinalSideWalls(bx, by, bz, moveDx, moveDz);

        for (int dist = 1; dist <= APPROACH_LOOKAHEAD_DIST; dist++) {
            int ax = bx + stepX * dist;
            int az = bz + stepZ * dist;
            if (isFullWallBlock(ax, by, az) || isFullWallBlock(ax, by + 1, az)) break;

            int aheadSides = countCardinalSideWalls(ax, by, az, moveDx, moveDz);
            if (aheadSides > prevSides && aheadSides >= 1) return dist;
            prevSides = aheadSides;
        }
        return 0;
    }
}
