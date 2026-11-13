package dev.aether.modules.pathfinding.pathing.processing.impl

import dev.aether.modules.pathfinding.movement.WalkabilityChecker
import dev.aether.modules.pathfinding.pathing.processing.Cost
import dev.aether.modules.pathfinding.pathing.processing.NodeProcessor
import dev.aether.modules.pathfinding.pathing.processing.context.EvaluationContext
import dev.aether.modules.pathfinding.provider.NavigationPoint
import dev.aether.modules.pathfinding.wrapper.PathPosition
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// most of this is derived from minecraft code or pathfinding writeups; if you change it, keep the idea or
// write a comment saying why. no magic numbers nobody can follow
class MinecraftPathProcessor @JvmOverloads constructor(
    private val checker: WalkabilityChecker? = null,
    maxJumpHeight: Double = DEFAULT_MOB_JUMP_HEIGHT
) : NodeProcessor {
    private val maxJumpHeight = max(DEFAULT_MOB_JUMP_HEIGHT, maxJumpHeight)

    // Cache for isInsideSolidSpace(prev) - prev is the same node for all neighbors in one expansion.
    // Avoids 2 redundant topY lookups per neighbor (saves 9 lookups per 10-neighbor expansion).
    private var lastCheckedPrev: PathPosition? = null
    private var lastPrevInsideSolid = false

    override fun isValid(context: EvaluationContext): Boolean {
        val provider = context.getNavigationPointProvider()
        val pos = context.getCurrentPathPosition()
        val prev = context.getPreviousPathPosition()
        val env = context.getEnvironmentContext()

        // Cheapest rejection first: check if player space is inside solid geometry
        if (prev != null) {
            // Cache prev's solid-space check - it's the same expanded node for all 10 neighbors
            if (prev !== lastCheckedPrev) {
                lastCheckedPrev = prev
                lastPrevInsideSolid = isInsideSolidSpace(prev)
            }
            if (lastPrevInsideSolid || isInsideSolidSpace(pos)) return false
        }

        val currentPoint = provider.getNavigationPoint(pos, env)

        if (!currentPoint.isTraversable()) return false
        if (prev == null) return true

        val level = if (checker != null) checker.getLevel() else mc.level
        if (level == null) return false

        val prevPoint = provider.getNavigationPoint(prev, env)
        val dy = pos.y - prev.y
        val dx = pos.flooredX() - prev.flooredX()
        val dz = pos.flooredZ() - prev.flooredZ()

        if (dy > maxJumpHeight) return false

        // Diagonal corner check: both intermediate corners must be traversable
        if (abs(dx) == 1 && abs(dz) == 1) {
            val corner1 = prev.add(dx.toDouble(), 0.0, 0.0)
            val corner2 = prev.add(0.0, 0.0, dz.toDouble())
            val c1 = provider.getNavigationPoint(corner1, env)
            val c2 = provider.getNavigationPoint(corner2, env)
            if (!c1.isTraversable() || !c2.isTraversable()) return false

            // Partial-floor extension: on a slab/stair the player's feet sit at flooredY+0.5,
            // so their bounding box extends into the (flooredY-1) block slice.  A full wall at
            // a corner at that lower slice will physically clip the player even though the
            // standard check (which only looks at flooredY and flooredY+1) passes.
            if (checker != null) {
                val fy = prev.flooredY() // == pos.flooredY() because dy==0 for diagonals
                val srcFloor = checker.getTopY(prev.flooredX(), fy - 1, prev.flooredZ())
                val dstFloor = checker.getTopY(pos.flooredX(), fy - 1, pos.flooredZ())
                if ((srcFloor > 0.1 && srcFloor < 0.95) || (dstFloor > 0.1 && dstFloor < 0.95)) {
                    val c1x = corner1.flooredX()
                    val c1z = corner1.flooredZ()
                    val c2x = corner2.flooredX()
                    val c2z = corner2.flooredZ()
                    if (checker.isFullWall(c1x, fy - 1, c1z) || checker.isFullWall(c2x, fy - 1, c2z)) {
                        return false
                    }
                }
            }
        }

        return when (java.lang.Double.compare(dy, 0.0)) {
            -1 -> if (dy < -0.5) // falling
                true
            else currentPoint.hasFloor() || prevPoint.hasFloor()
                || currentPoint.isClimbable() || prevPoint.isClimbable()
            1 -> if (dy > 0.5) // jumping/climbing
                prevPoint.hasFloor() || currentPoint.isClimbable()
            else currentPoint.hasFloor() || prevPoint.hasFloor()
                || currentPoint.isClimbable() || prevPoint.isClimbable()
            else -> currentPoint.hasFloor() || prevPoint.hasFloor()
                || currentPoint.isClimbable() || prevPoint.isClimbable()
        }
    }

    override fun calculateCostContribution(context: EvaluationContext): Cost {
        val level = if (checker != null) checker.getLevel() else mc.level
        if (level == null) return Cost.ZERO

        val currentPos = context.getCurrentPathPosition()
        val prevPos = context.getPreviousPathPosition() ?: return Cost.ZERO

        val provider = context.getNavigationPointProvider()
        val env = context.getEnvironmentContext()
        val currentPoint = provider.getNavigationPoint(currentPos, env)
        val prevPoint = provider.getNavigationPoint(prevPos, env)

        val dy = currentPoint.getFloorLevel() - prevPoint.getFloorLevel()
        var additionalCost = 0.0

        val cx = currentPos.flooredX()
        val cy = currentPos.flooredY()
        val cz = currentPos.flooredZ()
        val px = prevPos.flooredX()
        val py = prevPos.flooredY()
        val pz = prevPos.flooredZ()

        val fullStepSupport = isFullStepSupport(cx, cy - 1, cz)

        // Elevation cost: only true full-block ascents get heavy penalties.
        // Stairs/slabs are treated as partial ascents and stay cheap.
        if (dy > ASCENT_DY_THRESHOLD) {
            if (fullStepSupport) {
                additionalCost += FULL_STEP_ASCENT_DY_SCALE * dy + FULL_STEP_ASCENT_BASE_PENALTY
            }
        } else if (dy < DESCENT_DY_THRESHOLD) {
            additionalCost += DESCENT_COST_SCALE * abs(dy)
        }

        val moveDx = cx - px
        val moveDz = cz - pz

        // Cramped space penalty: ceiling proximity
        for (i in 2..3) {
            if (canOcclude(cx, cy + i, cz)) {
                additionalCost += 0.1 / i
            }
        }

        // Wall proximity gradient: single pass over all 8 neighbors to compute
        // cardinal walls, diagonal walls, entry-side walls, lateral imbalance, and side walls.
        val wallN = isFullWallBlock(cx, cy, cz - 1)
        val wallS = isFullWallBlock(cx, cy, cz + 1)
        val wallW = isFullWallBlock(cx - 1, cy, cz)
        val wallE = isFullWallBlock(cx + 1, cy, cz)
        val wallNW = isFullWallBlock(cx - 1, cy, cz - 1)
        val wallNE = isFullWallBlock(cx + 1, cy, cz - 1)
        val wallSW = isFullWallBlock(cx - 1, cy, cz + 1)
        val wallSE = isFullWallBlock(cx + 1, cy, cz + 1)

        val cardinalWalls = (if (wallN) 1 else 0) + (if (wallS) 1 else 0) + (if (wallW) 1 else 0) + (if (wallE) 1 else 0)
        val diagonalWalls = (if (wallNW) 1 else 0) + (if (wallNE) 1 else 0) + (if (wallSW) 1 else 0) + (if (wallSE) 1 else 0)

        val partialAscent = dy > PARTIAL_ASCENT_DY_THRESHOLD && !fullStepSupport
        var cardinalWallWeight = CARDINAL_WALL_COST
        var diagonalWallWeight = DIAGONAL_WALL_COST
        if (partialAscent) {
            cardinalWallWeight += PARTIAL_ASCENT_CARDINAL_EDGE_PENALTY
            diagonalWallWeight += PARTIAL_ASCENT_DIAGONAL_EDGE_PENALTY
        }
        additionalCost += cardinalWalls * cardinalWallWeight + diagonalWalls * diagonalWallWeight

        // Skip all corridor-centering logic when in open space - this is the
        // common case for large open-field searches and saves ~30 hashmap lookups per node.
        if (cardinalWalls > 0 || diagonalWalls > 0) {
            // Approach centering: boost wall penalties when a stairway or opening is ahead
            val ascentDist = detectPartialAscentAhead(cx, cy, cz, moveDx, moveDz)
            val openingDist = detectOpeningAhead(cx, cy, cz, moveDx, moveDz)
            val transitionDist = if (ascentDist > 0 && openingDist > 0)
                min(ascentDist, openingDist)
            else
                max(ascentDist, openingDist) // whichever is nonzero

            if (transitionDist > 0) {
                val multiplier = when (transitionDist) {
                    1 -> APPROACH_MULTIPLIER_DIST_1
                    2 -> APPROACH_MULTIPLIER_DIST_2
                    else -> APPROACH_MULTIPLIER_DIST_3
                }
                additionalCost += cardinalWalls * (CARDINAL_WALL_COST * multiplier)
                additionalCost += diagonalWalls * (DIAGONAL_WALL_COST * multiplier)
            }

            // Approach imbalance: penalize lateral asymmetry before an opening
            if (openingDist > 0 && isCardinalMove(moveDx, moveDz)) {
                val imbalance = countLateralImbalance(cx, cy, cz, moveDx, moveDz, wallN, wallS, wallW, wallE)
                val decay = when (openingDist) {
                    1 -> 1.0
                    2 -> 0.65
                    else -> 0.35
                }
                additionalCost += imbalance * APPROACH_OPENING_IMBALANCE_PENALTY * decay
            }

            if (partialAscent) {
                var entrySideWalls = countEntrySideWalls(px, py, pz, moveDx, moveDz)
                entrySideWalls += countEntrySideWalls(cx, cy, cz, moveDx, moveDz)
                additionalCost += entrySideWalls * PARTIAL_ASCENT_ENTRY_SIDE_PENALTY
            }

            if (isRoomOpeningTransition(px, py, pz, cx, cy, cz, moveDx, moveDz)) {
                val imbalance = countLateralImbalanceAt(px, py, pz, moveDx, moveDz) +
                    countLateralImbalanceAt(cx, cy, cz, moveDx, moveDz)
                additionalCost += imbalance * OPENING_ENTRY_IMBALANCE_PENALTY
            }
        }

        // Directional penalty: 3-hop weighted average direction vs current direction.
        val gpPos = context.getGrandparentPathPosition()
        val ggpPos = context.getGreatGrandparentPathPosition()

        // Inline direction computation - no ArrayList or double[] allocations
        var avgX = 0.0
        var avgZ = 0.0
        var totalW = 0.0
        if (gpPos != null) {
            val dx1 = prevPos.x - gpPos.x
            val dz1 = prevPos.z - gpPos.z
            val len1 = sqrt(dx1 * dx1 + dz1 * dz1)
            if (len1 > 0.1) {
                avgX += dx1 / len1
                avgZ += dz1 / len1
                totalW += 1.0
            }
        }
        if (ggpPos != null && gpPos != null) {
            val dx2 = gpPos.x - ggpPos.x
            val dz2 = gpPos.z - ggpPos.z
            val len2 = sqrt(dx2 * dx2 + dz2 * dz2)
            if (len2 > 0.1) {
                avgX += dx2 / len2 * 0.6
                avgZ += dz2 / len2 * 0.6
                totalW += 0.6
            }
        }
        if (totalW > 0.1) {
            avgX /= totalW
            avgZ /= totalW
            val cdx = currentPos.x - prevPos.x
            val cdz = currentPos.z - prevPos.z
            val curLen = sqrt(cdx * cdx + cdz * cdz)
            if (curLen > 0.1) {
                val dot = (cdx / curLen) * avgX + (cdz / curLen) * avgZ
                val angleDeg = Math.toDegrees(acos(max(-1.0, min(1.0, dot))))
                if (angleDeg < 5.0) {
                    additionalCost -= 0.3 // straightness bonus
                } else {
                    additionalCost += angleDeg / 90.0 * 0.8
                }
            }
        }

        val finalCost = max(0.0, additionalCost)
        return Cost.of(finalCost)
    }

    // --- Checker-backed query methods (int coords, no BlockPos allocation) --

    private fun isFullWallBlock(x: Int, y: Int, z: Int): Boolean {
        if (checker != null) return checker.isFullWallBlock(x, y, z)
        val level = mc.level ?: return false
        return isFullWallBlockStatic(level, BlockPos(x, y, z))
    }

    private fun canOcclude(x: Int, y: Int, z: Int): Boolean {
        if (checker != null) return checker.canOcclude(x, y, z)
        val level = mc.level ?: return false
        return level.getBlockState(BlockPos(x, y, z)).canOcclude()
    }

    private fun isFullStepSupport(x: Int, y: Int, z: Int): Boolean {
        if (checker != null) {
            return checker.isFullWall(x, y, z)
        }
        val level = mc.level ?: return false
        return isFullWallBlockStatic(level, BlockPos(x, y, z))
    }

    private fun isInsideSolidSpace(pos: PathPosition): Boolean {
        val fx = pos.flooredX()
        val fy = pos.flooredY()
        val fz = pos.flooredZ()
        if (checker != null) {
            // Treat passable partial blocks such as carpet as empty player space.
            return isBlockingPlayerSpace(fx, fy, fz) || isBlockingPlayerSpace(fx, fy + 1, fz)
        }
        val level = mc.level ?: return true
        val feet = BlockPos(fx, fy, fz)
        val head = feet.above()
        val empty = CollisionContext.empty()
        return !level.getBlockState(feet).getCollisionShape(level, feet, empty).isEmpty ||
            !level.getBlockState(head).getCollisionShape(level, head, empty).isEmpty
    }

    private fun isBlockingPlayerSpace(x: Int, y: Int, z: Int): Boolean {
        val c = checker ?: return false
        return !c.isPassable(x, y, z) && c.getTopY(x, y, z) > 0.0
    }

    // --- Wall counting helpers (int coords) --------------------------------

    private fun countEntrySideWalls(x: Int, y: Int, z: Int, moveDx: Int, moveDz: Int): Int {
        if (abs(moveDx) > abs(moveDz)) {
            var walls = 0
            if (isFullWallBlock(x, y, z - 1)) walls++
            if (isFullWallBlock(x, y, z + 1)) walls++
            return walls
        }
        if (abs(moveDz) > abs(moveDx)) {
            var walls = 0
            if (isFullWallBlock(x - 1, y, z)) walls++
            if (isFullWallBlock(x + 1, y, z)) walls++
            return walls
        }
        // Diagonal/ambiguous entry
        var walls = 0
        if (isFullWallBlock(x, y, z - 1)) walls++
        if (isFullWallBlock(x, y, z + 1)) walls++
        if (isFullWallBlock(x - 1, y, z)) walls++
        if (isFullWallBlock(x + 1, y, z)) walls++
        return walls
    }

    private fun isRoomOpeningTransition(
        px: Int, py: Int, pz: Int, cx: Int, cy: Int, cz: Int,
        moveDx: Int, moveDz: Int
    ): Boolean {
        if (!isCardinalMove(moveDx, moveDz)) return false
        val prevSides = countCardinalSideWalls(px, py, pz, moveDx, moveDz)
        val curSides = countCardinalSideWalls(cx, cy, cz, moveDx, moveDz)
        return curSides > prevSides && curSides >= 1
    }

    private fun countLateralImbalance(
        x: Int, y: Int, z: Int, moveDx: Int, moveDz: Int,
        wallN: Boolean, wallS: Boolean, wallW: Boolean, wallE: Boolean
    ): Int {
        if (!isCardinalMove(moveDx, moveDz)) return 0
        val sideA: Boolean
        val sideB: Boolean
        if (abs(moveDx) > abs(moveDz)) {
            sideA = wallN
            sideB = wallS
        } else {
            sideA = wallW
            sideB = wallE
        }
        return if (sideA xor sideB) 1 else 0
    }

    private fun countLateralImbalanceAt(x: Int, y: Int, z: Int, moveDx: Int, moveDz: Int): Int {
        if (!isCardinalMove(moveDx, moveDz)) return 0
        val sideA: Boolean
        val sideB: Boolean
        if (abs(moveDx) > abs(moveDz)) {
            sideA = isFullWallBlock(x, y, z - 1)
            sideB = isFullWallBlock(x, y, z + 1)
        } else {
            sideA = isFullWallBlock(x - 1, y, z)
            sideB = isFullWallBlock(x + 1, y, z)
        }
        return if (sideA xor sideB) 1 else 0
    }

    private fun countCardinalSideWalls(x: Int, y: Int, z: Int, moveDx: Int, moveDz: Int): Int {
        if (!isCardinalMove(moveDx, moveDz)) return 0
        if (abs(moveDx) > abs(moveDz)) {
            var walls = 0
            if (isFullWallBlock(x, y, z - 1)) walls++
            if (isFullWallBlock(x, y, z + 1)) walls++
            return walls
        }
        var walls = 0
        if (isFullWallBlock(x - 1, y, z)) walls++
        if (isFullWallBlock(x + 1, y, z)) walls++
        return walls
    }

    private fun detectPartialAscentAhead(bx: Int, by: Int, bz: Int, moveDx: Int, moveDz: Int): Int {
        if (moveDx == 0 && moveDz == 0) return 0
        val stepX = Integer.signum(moveDx)
        val stepZ = Integer.signum(moveDz)

        for (dist in 1..APPROACH_LOOKAHEAD_DIST) {
            val ax = bx + stepX * dist
            val az = bz + stepZ * dist

            // Full wall ahead blocks further scanning
            if (isFullWallBlock(ax, by, az)) break

            val belowY = by - 1
            if (checker != null) {
                if (checker.isAir(ax, belowY, az)) continue
                if (!checker.isFullWallBlock(ax, belowY, az)) {
                    val topY = checker.getTopY(ax, belowY, az)
                    if (topY > 0.2 && topY < 0.95) return dist
                }
            } else {
                val level = mc.level ?: return 0
                val aheadBelow = BlockPos(ax, belowY, az)
                val belowState = level.getBlockState(aheadBelow)
                if (belowState.isAir) continue
                if (!isFullWallBlockStatic(level, aheadBelow)) {
                    val shape = belowState.getCollisionShape(level, aheadBelow)
                    if (!shape.isEmpty) {
                        val topY = shape.bounds().maxY
                        if (topY > 0.2 && topY < 0.95) return dist
                    }
                }
            }
        }
        return 0
    }

    private fun detectOpeningAhead(bx: Int, by: Int, bz: Int, moveDx: Int, moveDz: Int): Int {
        if (!isCardinalMove(moveDx, moveDz)) return 0
        val stepX = Integer.signum(moveDx)
        val stepZ = Integer.signum(moveDz)

        var prevSides = countCardinalSideWalls(bx, by, bz, moveDx, moveDz)

        for (dist in 1..APPROACH_LOOKAHEAD_DIST) {
            val ax = bx + stepX * dist
            val az = bz + stepZ * dist
            if (isFullWallBlock(ax, by, az) || isFullWallBlock(ax, by + 1, az)) break

            val aheadSides = countCardinalSideWalls(ax, by, az, moveDx, moveDz)
            if (aheadSides > prevSides && aheadSides >= 1) return dist
            prevSides = aheadSides
        }
        return 0
    }

    companion object {
        private const val DEFAULT_MOB_JUMP_HEIGHT = 1.125 // WalkNodeEvaluator
        private const val ASCENT_DY_THRESHOLD = 0.5
        private const val PARTIAL_ASCENT_DY_THRESHOLD = 0.2
        private const val DESCENT_DY_THRESHOLD = -0.1
        private const val FULL_STEP_ASCENT_DY_SCALE = 0.5
        private const val FULL_STEP_ASCENT_BASE_PENALTY = 2.0
        private const val DESCENT_COST_SCALE = 0.1

        private const val CARDINAL_WALL_COST = 0.55
        private const val DIAGONAL_WALL_COST = 0.25

        // Partial ascents (slabs/stairs) should prefer centered entry/lines over edge hugging.
        private const val PARTIAL_ASCENT_CARDINAL_EDGE_PENALTY = 0.32
        private const val PARTIAL_ASCENT_DIAGONAL_EDGE_PENALTY = 0.12
        private const val PARTIAL_ASCENT_ENTRY_SIDE_PENALTY = 0.28

        // Openings between spaces should be entered centered; penalize side-biased approach lines.
        private const val OPENING_ENTRY_IMBALANCE_PENALTY = 0.24

        // Approach centering: how far ahead to scan for upcoming transitions
        private const val APPROACH_LOOKAHEAD_DIST = 3

        // Multipliers for boosting wall-proximity cost when a transition is ahead.
        // Decay with distance: closer = stronger centering pressure.
        // These multiply the existing CARDINAL_WALL_COST / DIAGONAL_WALL_COST.
        private const val APPROACH_MULTIPLIER_DIST_1 = 0.85 // +0.47/cardinal wall
        private const val APPROACH_MULTIPLIER_DIST_2 = 0.55 // +0.30/cardinal wall
        private const val APPROACH_MULTIPLIER_DIST_3 = 0.30 // +0.17/cardinal wall

        // Lateral imbalance penalty when approaching an opening (applied before reaching it)
        private const val APPROACH_OPENING_IMBALANCE_PENALTY = 0.18

        private val mc = Minecraft.getInstance()

        @JvmStatic
        fun computeStaticCost(level: Level, bp: BlockPos): Double {
            var cost = 0.0
            for (i in 2..3) {
                if (level.getBlockState(bp.above(i)).canOcclude()) cost += 0.1 / i
            }
            var cardinalWalls = 0
            if (isFullWallBlockStatic(level, bp.west())) cardinalWalls++
            if (isFullWallBlockStatic(level, bp.east())) cardinalWalls++
            if (isFullWallBlockStatic(level, bp.north())) cardinalWalls++
            if (isFullWallBlockStatic(level, bp.south())) cardinalWalls++
            var diagonalWalls = 0
            if (isFullWallBlockStatic(level, bp.north().west())) diagonalWalls++
            if (isFullWallBlockStatic(level, bp.north().east())) diagonalWalls++
            if (isFullWallBlockStatic(level, bp.south().west())) diagonalWalls++
            if (isFullWallBlockStatic(level, bp.south().east())) diagonalWalls++
            cost += cardinalWalls * 0.4 + diagonalWalls * 0.15
            return cost
        }

        private fun isFullWallBlockStatic(level: Level, pos: BlockPos): Boolean {
            val state = level.getBlockState(pos)
            return state.canOcclude() && state.isCollisionShapeFullBlock(level, pos)
        }

        private fun isCardinalMove(moveDx: Int, moveDz: Int): Boolean {
            return (moveDx == 0) != (moveDz == 0)
        }
    }
}
