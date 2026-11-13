package dev.aether.modules.pathfinding.debug

import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.util.BlockPosUtil
import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.LinkedHashSet

class PathVisualizer private constructor() {
    companion object {
        private const val MAX_EXPLORED = 3000
        private const val MAX_RENDER = 300
        private const val MAX_EXPLORED_DRAW = 1000
        private const val BEZIER_TANGENT = 0.4
        private const val BEZIER_STEPS = 8
        private const val MAX_CAMERA_RENDER = 480

        private var enabled = false
        private var transientSessionActive = false
        private var currentPath: List<Node> = Collections.emptyList()
        private var currentWaypointIndex = 0
        private var cameraPath: List<Vec3> = Collections.emptyList()
        private var currentCameraRailIndex = -1
        private var walkingAimPoint: Vec3? = null
        private var walkingDirection: Vec3 = Vec3.ZERO

        private val exploredNodes: MutableSet<Long> =
            Collections.synchronizedSet(LinkedHashSet<Long>())

        private var bezierCache: Array<Array<Vec3>?>? = null

        @JvmStatic
        fun register() {}

        @JvmStatic
        fun captureCamera(mc: Minecraft) {}

        @JvmStatic
        fun beginTransientSession() {
            transientSessionActive = true
            clear()
        }

        @JvmStatic
        fun endTransientSession() {
            transientSessionActive = false
        }

        @JvmStatic
        fun setPath(path: List<Node>?, wpIndex: Int) {
            currentPath = path ?: Collections.emptyList()
            currentWaypointIndex = wpIndex
            bezierCache = null
            walkingAimPoint = null
            walkingDirection = Vec3.ZERO
        }

        @JvmStatic
        fun setCameraPath(path: List<Vec3>?) {
            cameraPath = path ?: Collections.emptyList()
            if (currentCameraRailIndex >= cameraPath.size) currentCameraRailIndex = -1
        }

        @JvmStatic
        fun addExplored(x: Int, y: Int, z: Int) {
            if (exploredNodes.size < MAX_EXPLORED) {
                exploredNodes.add(BlockPosUtil.pack(x, y, z))
            }
        }

        @JvmStatic
        fun toggle() {
            enabled = !enabled
            if (!enabled) clear()
        }

        @JvmStatic
        fun updateExecution(wpIndex: Int, camTargetIdx: Int) {
            currentWaypointIndex = wpIndex
            if (camTargetIdx >= 0) {
                currentCameraRailIndex = camTargetIdx
            }
        }

        @JvmStatic
        fun updateCameraExecution(camRailIdx: Int) {
            currentCameraRailIndex = camRailIdx
        }

        @JvmStatic
        fun updateWalkingTargets(aimPoint: Vec3, direction: Vec3) {
            walkingAimPoint = aimPoint
            walkingDirection = direction
        }

        @JvmStatic
        fun clear() {
            currentPath = Collections.emptyList()
            cameraPath = Collections.emptyList()
            currentWaypointIndex = 0
            currentCameraRailIndex = -1
            walkingAimPoint = null
            walkingDirection = Vec3.ZERO
            exploredNodes.clear()
            bezierCache = null
        }

        @JvmStatic
        fun isEnabled(): Boolean = enabled

        @JvmStatic
        fun isTransientSessionActive(): Boolean = transientSessionActive

        @JvmStatic
        fun shouldRender(): Boolean = enabled || transientSessionActive

        @JvmStatic
        fun shouldCaptureExploredNodes(): Boolean = shouldRender()

        @JvmStatic
        fun renderWorld() {
            if (!shouldRender()) return
            val mc = Minecraft.getInstance()
            if (mc.player == null) return

            val path = currentPath
            val limit = Math.min(path.size, MAX_RENDER)
            val last = path.size - 1

            // Explored nodes
            var hasExplored: Boolean
            synchronized(exploredNodes) { hasExplored = exploredNodes.isNotEmpty() }
            if (hasExplored) {
                val exploredStyle = GizmoStyle.strokeAndFill(
                    ARGB.color(80, 255, 48, 32), 1.0f, ARGB.color(15, 255, 48, 32)
                )
                synchronized(exploredNodes) {
                    var count = 0
                    for (key in exploredNodes) {
                        if (count++ >= MAX_EXPLORED_DRAW) break
                        val x = BlockPosUtil.unpackX(key)
                        val y = BlockPosUtil.unpackY(key)
                        val z = BlockPosUtil.unpackZ(key)
                        val props = Gizmos.cuboid(AABB(x.toDouble(), (y - 1).toDouble(), z.toDouble(), (x + 1).toDouble(), y.toDouble(), (z + 1).toDouble()), exploredStyle)
                        props.setAlwaysOnTop()
                    }
                }
            }

            if (limit == 0) return

            if (bezierCache == null && limit >= 2) {
                bezierCache = buildBezierCache(path, limit)
            }

            // Keynode boxes only (skip intermediates)
            for (i in 0 until limit) {
                val n = path[i]
                if (!n.isKeynode) continue
                val argb = nodeArgb(n)
                val style = GizmoStyle.strokeAndFill(argb[0], 1.5f, argb[1])
                val nx = n.position.flooredX()
                val ny = n.position.flooredY()
                val nz = n.position.flooredZ()
                val props = Gizmos.cuboid(AABB(nx.toDouble(), (ny - 1).toDouble(), nz.toDouble(), (nx + 1).toDouble(), ny.toDouble(), (nz + 1).toDouble()), style)
                props.setAlwaysOnTop()
            }

            // Bezier path lines
            val cache = bezierCache
            if (cache != null) {
                for (i in 0 until limit - 1) {
                    val pts = cache[i] ?: continue
                    val lineArgb = lineArgb(i, last)
                    for (j in 0 until BEZIER_STEPS - 1) {
                        val lp = Gizmos.line(pts[j], pts[j + 1], lineArgb, 2.5f)
                        lp.setAlwaysOnTop()
                    }
                }
            }

            renderCameraRail()
            val aim = walkingAimPoint
            if (aim != null) {
                Gizmos.cuboid(
                    AABB(aim.x - 0.12, aim.y - 0.12, aim.z - 0.12, aim.x + 0.12, aim.y + 0.12, aim.z + 0.12),
                    GizmoStyle.stroke(ARGB.color(255, 255, 100, 220), 2.5f)
                ).setAlwaysOnTop()
                val feet = mc.player!!.position().add(0.0, 0.15, 0.0)
                Gizmos.line(
                    feet, feet.add(walkingDirection.scale(1.5)),
                    ARGB.color(255, 100, 255, 160), 3.0f
                ).setAlwaysOnTop()
            }
        }

        private fun renderCameraRail() {
            if (cameraPath.isEmpty()) return

            val limit = Math.min(cameraPath.size, MAX_CAMERA_RENDER)

            for (i in 0 until limit - 1) {
                val a = cameraPath[i]
                val b = cameraPath[i + 1]
                var argb = ARGB.color(200, 120, 220, 255)
                if (i < currentCameraRailIndex) argb = ARGB.color(120, 120, 140, 160)
                val lp = Gizmos.line(a, b, argb, 1.5f)
                lp.setAlwaysOnTop()
            }

            for (i in 0 until limit) {
                val p = cameraPath[i]
                val active = i == currentCameraRailIndex
                val style = if (active)
                    GizmoStyle.strokeAndFill(ARGB.color(255, 255, 120, 255), 2.6f, ARGB.color(110, 175, 60, 255))
                else
                    GizmoStyle.strokeAndFill(ARGB.color(160, 145, 145, 145), 1.0f, ARGB.color(25, 120, 120, 120))
                val r = if (active) 0.22 else 0.08
                val props = Gizmos.cuboid(AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r), style)
                props.setAlwaysOnTop()
            }
        }

        private fun buildBezierCache(path: List<Node>, limit: Int): Array<Array<Vec3>?> {
            val cache = arrayOfNulls<Array<Vec3>>(limit - 1)
            for (i in 0 until limit - 1) {
                val a = nodeFloorCenter(path[i])
                val b = nodeFloorCenter(path[i + 1])
                var prev = if (i > 0) nodeFloorCenter(path[i - 1]) else Vec3(2 * a.x - b.x, 2 * a.y - b.y, 2 * a.z - b.z)
                var next = if (i + 2 < limit) nodeFloorCenter(path[i + 2]) else Vec3(2 * b.x - a.x, 2 * b.y - a.y, 2 * b.z - a.z)
                var dirIn = safeNormalize(a.subtract(prev))
                var dirOut = safeNormalize(next.subtract(b))
                val ab = safeNormalize(b.subtract(a))
                if (dirIn.lengthSqr() < 0.001) dirIn = ab
                if (dirOut.lengthSqr() < 0.001) dirOut = ab
                val cp1 = a.add(dirIn.scale(BEZIER_TANGENT))
                val cp2 = b.subtract(dirOut.scale(BEZIER_TANGENT))
                val pts = arrayOfNulls<Vec3>(BEZIER_STEPS)
                for (j in 0 until BEZIER_STEPS) {
                    pts[j] = evalCubicBezier(a, cp1, cp2, b, j / (BEZIER_STEPS - 1).toDouble())
                }
                @Suppress("UNCHECKED_CAST")
                cache[i] = pts as Array<Vec3>
            }
            return cache
        }

        private fun nodeFloorCenter(n: Node): Vec3 =
            Vec3(n.position.flooredX() + 0.5, n.position.flooredY() - 0.5, n.position.flooredZ() + 0.5)

        private fun safeNormalize(v: Vec3): Vec3 {
            val len = v.length()
            return if (len < 0.001) Vec3.ZERO else v.scale(1.0 / len)
        }

        private fun evalCubicBezier(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Vec3 {
            val mt = 1.0 - t
            val c0 = mt * mt * mt
            val c1 = 3 * mt * mt * t
            val c2 = 3 * mt * t * t
            val c3 = t * t * t
            return Vec3(
                c0 * p0.x + c1 * p1.x + c2 * p2.x + c3 * p3.x,
                c0 * p0.y + c1 * p1.y + c2 * p2.y + c3 * p3.y,
                c0 * p0.z + c1 * p1.z + c2 * p2.z + c3 * p3.z
            )
        }

        private fun nodeArgb(n: Node): IntArray {
            if (!n.isKeynode) {
                // Intermediate tracking node - light pink
                return intArrayOf(ARGB.color(200, 255, 180, 200), ARGB.color(50, 255, 180, 200))
            }
            if (n.moveType == Node.MoveType.ETHERWARP) {
                return intArrayOf(ARGB.color(235, 80, 220, 255), ARGB.color(80, 80, 220, 255))
            }
            // Keynode - purple
            return intArrayOf(ARGB.color(230, 180, 50, 255), ARGB.color(70, 180, 50, 255))
        }

        private fun lineArgb(i: Int, last: Int): Int {
            if (i >= last - 1) return ARGB.color(217, 51, 128, 255)
            if (i < currentWaypointIndex) return ARGB.color(130, 128, 128, 128)
            if (i == currentWaypointIndex) return ARGB.color(217, 255, 255, 0)
            return ARGB.color(217, 26, 204, 26)
        }
    }
}
