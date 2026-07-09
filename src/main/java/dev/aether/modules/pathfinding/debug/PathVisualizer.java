package dev.aether.modules.pathfinding.debug;

import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.util.BlockPosUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public final class PathVisualizer {

    private static final int MAX_EXPLORED      = 3000;
    private static final int MAX_RENDER        = 300;
    private static final int MAX_EXPLORED_DRAW = 1000;
    private static final double BEZIER_TANGENT = 0.4;
    private static final int    BEZIER_STEPS   = 8;
    private static final int MAX_CAMERA_RENDER  = 480;

    private static boolean    enabled                = false;
    private static boolean    transientSessionActive = false;
    private static List<Node> currentPath           = Collections.emptyList();
    private static int        currentWaypointIndex  = 0;
    private static List<Vec3> cameraPath             = Collections.emptyList();
    private static int        currentCameraRailIndex = -1;

    private static final Set<Long> exploredNodes =
            Collections.synchronizedSet(new LinkedHashSet<>());

    private static Vec3[][] bezierCache = null;

    private PathVisualizer() {}

    public static void register() {}
    public static void captureCamera(Minecraft mc) {}

    public static void beginTransientSession() {
        transientSessionActive = true;
        clear();
    }

    public static void endTransientSession() {
        transientSessionActive = false;
    }

    public static void setPath(List<Node> path, int wpIndex) {
        currentPath          = (path != null) ? path : Collections.emptyList();
        currentWaypointIndex = wpIndex;
        bezierCache          = null;
    }

    public static void setCameraPath(List<Vec3> path) {
        cameraPath = (path != null) ? path : Collections.emptyList();
        if (currentCameraRailIndex >= cameraPath.size()) currentCameraRailIndex = -1;
    }

    public static void addExplored(int x, int y, int z) {
        if (exploredNodes.size() < MAX_EXPLORED) {
            exploredNodes.add(BlockPosUtil.pack(x, y, z));
        }
    }

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) clear();
    }

    public static void updateExecution(int wpIndex, int camTargetIdx) {
        currentWaypointIndex  = wpIndex;
        if (camTargetIdx >= 0) {
            currentCameraRailIndex = camTargetIdx;
        }
    }

    public static void updateCameraExecution(int camRailIdx) {
        currentCameraRailIndex = camRailIdx;
    }

    public static void clear() {
        currentPath           = Collections.emptyList();
        cameraPath            = Collections.emptyList();
        currentWaypointIndex  = 0;
        currentCameraRailIndex = -1;
        exploredNodes.clear();
        bezierCache = null;
    }

    public static boolean isEnabled() { return enabled; }

    public static boolean isTransientSessionActive() {
        return transientSessionActive;
    }

    public static boolean shouldRender() {
        return enabled || transientSessionActive;
    }

    public static boolean shouldCaptureExploredNodes() {
        return shouldRender();
    }

    public static void renderWorld() {
        if (!shouldRender()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        List<Node> path  = currentPath;
        int        limit = Math.min(path.size(), MAX_RENDER);
        int        last  = path.size() - 1;

        // Explored nodes
        boolean hasExplored;
        synchronized (exploredNodes) { hasExplored = !exploredNodes.isEmpty(); }
        if (hasExplored) {
            GizmoStyle exploredStyle = GizmoStyle.strokeAndFill(
                    ARGB.color(80, 255, 48, 32), 1.0f, ARGB.color(15, 255, 48, 32));
            synchronized (exploredNodes) {
                int count = 0;
                for (long key : exploredNodes) {
                    if (count++ >= MAX_EXPLORED_DRAW) break;
                    int x = BlockPosUtil.unpackX(key);
                    int y = BlockPosUtil.unpackY(key);
                    int z = BlockPosUtil.unpackZ(key);
                    var props = Gizmos.cuboid(new AABB(x, y - 1, z, x + 1, y, z + 1), exploredStyle);
                    props.setAlwaysOnTop();
                }
            }
        }

        if (limit == 0) return;

        if (bezierCache == null && limit >= 2) {
            bezierCache = buildBezierCache(path, limit);
        }

        // Keynode boxes only (skip intermediates)
        for (int i = 0; i < limit; i++) {
            Node n = path.get(i);
            if (!n.isKeynode) continue;
            int[] argb = nodeArgb(n);
            GizmoStyle style = GizmoStyle.strokeAndFill(argb[0], 1.5f, argb[1]);
            int nx = n.position.flooredX();
            int ny = n.position.flooredY();
            int nz = n.position.flooredZ();
            var props = Gizmos.cuboid(new AABB(nx, ny - 1, nz, nx + 1, ny, nz + 1), style);
            props.setAlwaysOnTop();
        }

        // Bezier path lines
        if (bezierCache != null) {
            for (int i = 0; i < limit - 1; i++) {
                if (bezierCache[i] == null) continue;
                int lineArgb = lineArgb(i, last);
                Vec3[] pts = bezierCache[i];
                for (int j = 0; j < BEZIER_STEPS - 1; j++) {
                    var lp = Gizmos.line(pts[j], pts[j + 1], lineArgb, 2.5f);
                    lp.setAlwaysOnTop();
                }
            }
        }

        renderCameraRail();
    }

    private static void renderCameraRail() {
        if (cameraPath.isEmpty()) return;

        int limit = Math.min(cameraPath.size(), MAX_CAMERA_RENDER);

        for (int i = 0; i < limit - 1; i++) {
            Vec3 a = cameraPath.get(i);
            Vec3 b = cameraPath.get(i + 1);
            int argb = ARGB.color(200, 120, 220, 255);
            if (i < currentCameraRailIndex) argb = ARGB.color(120, 120, 140, 160);
            var lp = Gizmos.line(a, b, argb, 1.5f);
            lp.setAlwaysOnTop();
        }

        for (int i = 0; i < limit; i++) {
            Vec3 p = cameraPath.get(i);
            boolean active = i == currentCameraRailIndex;
            GizmoStyle style = active
                    ? GizmoStyle.strokeAndFill(ARGB.color(255, 255, 120, 255), 2.6f, ARGB.color(110, 175, 60, 255))
                    : GizmoStyle.strokeAndFill(ARGB.color(160, 145, 145, 145), 1.0f, ARGB.color(25, 120, 120, 120));
            double r = active ? 0.22 : 0.08;
            var props = Gizmos.cuboid(new AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r), style);
            props.setAlwaysOnTop();
        }
    }

    private static Vec3[][] buildBezierCache(List<Node> path, int limit) {
        Vec3[][] cache = new Vec3[limit - 1][];
        for (int i = 0; i < limit - 1; i++) {
            Vec3 a = nodeFloorCenter(path.get(i));
            Vec3 b = nodeFloorCenter(path.get(i + 1));
            Vec3 prev = (i > 0)
                    ? nodeFloorCenter(path.get(i - 1))
                    : new Vec3(2*a.x - b.x, 2*a.y - b.y, 2*a.z - b.z);
            Vec3 next = (i + 2 < limit)
                    ? nodeFloorCenter(path.get(i + 2))
                    : new Vec3(2*b.x - a.x, 2*b.y - a.y, 2*b.z - a.z);
            Vec3 dirIn  = safeNormalize(a.subtract(prev));
            Vec3 dirOut = safeNormalize(next.subtract(b));
            Vec3 ab     = safeNormalize(b.subtract(a));
            if (dirIn.lengthSqr()  < 0.001) dirIn  = ab;
            if (dirOut.lengthSqr() < 0.001) dirOut = ab;
            Vec3 cp1 = a.add(dirIn.scale(BEZIER_TANGENT));
            Vec3 cp2 = b.subtract(dirOut.scale(BEZIER_TANGENT));
            Vec3[] pts = new Vec3[BEZIER_STEPS];
            for (int j = 0; j < BEZIER_STEPS; j++) {
                pts[j] = evalCubicBezier(a, cp1, cp2, b, j / (double)(BEZIER_STEPS - 1));
            }
            cache[i] = pts;
        }
        return cache;
    }

    private static Vec3 nodeFloorCenter(Node n) {
        return new Vec3(n.position.flooredX() + 0.5, n.position.flooredY() - 0.5, n.position.flooredZ() + 0.5);
    }

    private static Vec3 safeNormalize(Vec3 v) {
        double len = v.length();
        return len < 0.001 ? Vec3.ZERO : v.scale(1.0 / len);
    }

    private static Vec3 evalCubicBezier(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double mt = 1.0 - t;
        double c0 = mt*mt*mt, c1 = 3*mt*mt*t, c2 = 3*mt*t*t, c3 = t*t*t;
        return new Vec3(c0*p0.x+c1*p1.x+c2*p2.x+c3*p3.x,
                        c0*p0.y+c1*p1.y+c2*p2.y+c3*p3.y,
                        c0*p0.z+c1*p1.z+c2*p2.z+c3*p3.z);
    }

    private static int[] nodeArgb(Node n) {
        if (!n.isKeynode) {
            // Intermediate tracking node - light pink
            return new int[]{ ARGB.color(200, 255, 180, 200), ARGB.color(50, 255, 180, 200) };
        }
        if (n.moveType == Node.MoveType.ETHERWARP) {
            return new int[]{ ARGB.color(235, 80, 220, 255), ARGB.color(80, 80, 220, 255) };
        }
        // Keynode - purple
        return new int[]{ ARGB.color(230, 180, 50, 255), ARGB.color(70, 180, 50, 255) };
    }

    private static int lineArgb(int i, int last) {
        if (i >= last - 1)             return ARGB.color(217,  51,128,255);
        if (i < currentWaypointIndex)  return ARGB.color(130, 128,128,128);
        if (i == currentWaypointIndex) return ARGB.color(217, 255,255,  0);
        return                                ARGB.color(217,  26,204, 26);
    }
}
