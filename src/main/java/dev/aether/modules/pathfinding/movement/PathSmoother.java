package dev.aether.modules.pathfinding.movement;

import dev.aether.modules.pathfinding.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements a string-pulling path smoother using Bresenham 3-D line-of-sight tests.
 */
public final class PathSmoother {

    // Adaptive smoothing budget: open areas can skip farther than tight corridors.
    private static final int OPEN_SKIP_BUDGET = 12;
    private static final int MID_SKIP_BUDGET = 7;
    private static final int TIGHT_SKIP_BUDGET = 4;

    private static final int OPEN_WALL_SCORE_MAX = 2;
    private static final int MID_WALL_SCORE_MAX = 6;
    private static final double CONSTRAINED_CORNER_ANGLE_DEG = 35.0;

    private PathSmoother() {}

    public static List<Node> smooth(List<Node> raw, WalkabilityChecker checker) {
        if (raw == null || raw.size() <= 2) return raw;

        List<Node> result = new ArrayList<>();
        result.add(raw.get(0));

        int anchorIdx = 0;

        while (anchorIdx < raw.size() - 1) {
            Node anchor   = raw.get(anchorIdx);
            int  furthest = anchorIdx + 1;

            int budget = computeAdaptiveSkipBudget(anchor, checker);
            int maxCand = Math.min(raw.size() - 1, anchorIdx + budget);

            for (int cand = anchorIdx + 2; cand <= maxCand; cand++) {
                Node prevNode  = raw.get(cand - 1);
                Node candidate = raw.get(cand);
                // Never skip over a Y-changing node
                if (prevNode.position.flooredY() != anchor.position.flooredY()
                        || candidate.position.flooredY() != anchor.position.flooredY()) break;
                if (wouldSkipConstrainedCorner(raw, anchorIdx, cand, checker)) break;
                if (hasLineOfSight(anchor, candidate, checker)) {
                    furthest = cand;
                } else {
                    break;
                }
            }

            anchorIdx = furthest;
            result.add(raw.get(anchorIdx));
        }

        return result;
    }

    public static List<Node> smoothFly(List<Node> raw, WalkabilityChecker checker) {
        if (raw == null || raw.size() <= 2) return raw;

        List<Node> result = new ArrayList<>();
        result.add(raw.get(0));

        int anchorIdx = 0;
        while (anchorIdx < raw.size() - 1) {
            Node anchor   = raw.get(anchorIdx);
            int  furthest = anchorIdx + 1;

            for (int cand = anchorIdx + 2; cand < raw.size(); cand++) {
                Node candidate = raw.get(cand);
                if (hasFlyLineOfSight(anchor, candidate, checker)) {
                    furthest = cand;
                } else {
                    break;
                }
            }

            anchorIdx = furthest;
            result.add(raw.get(anchorIdx));
        }

        return result;
    }

    private static boolean hasLineOfSight(Node from, Node to, WalkabilityChecker checker) {
        int x0 = from.position.flooredX(), y0 = from.position.flooredY(), z0 = from.position.flooredZ();
        int x1 = to.position.flooredX(),   y1 = to.position.flooredY(),   z1 = to.position.flooredZ();

        int dx = Math.abs(x1-x0), dy = Math.abs(y1-y0), dz = Math.abs(z1-z0);
        int sx = x0<x1?1:-1, sy = y0<y1?1:-1, sz = z0<z1?1:-1;
        int x = x0, y = y0, z = z0;

        if (dx >= dy && dx >= dz) {
            int e1 = 2*dy-dx, e2 = 2*dz-dx;
            for (int i = 0; i < dx; i++) {
                if (!clearForSmoothing(checker, x, y, z)) return false;
                int prevX = x, prevZ = z;
                if (e1>0){y+=sy;e1-=2*dx;} if(e2>0){z+=sz;e2-=2*dx;}
                e1+=2*dy; e2+=2*dz; x+=sx;
                if (diagonalStepBlocked(checker, prevX, prevZ, x, y, z)) return false;
            }
        } else if (dy >= dx && dy >= dz) {
            int e1 = 2*dx-dy, e2 = 2*dz-dy;
            for (int i = 0; i < dy; i++) {
                if (!clearForSmoothing(checker, x, y, z)) return false;
                int prevX = x, prevZ = z;
                if (e1>0){x+=sx;e1-=2*dy;} if(e2>0){z+=sz;e2-=2*dy;}
                e1+=2*dx; e2+=2*dz; y+=sy;
                if (diagonalStepBlocked(checker, prevX, prevZ, x, y, z)) return false;
            }
        } else {
            int e1 = 2*dx-dz, e2 = 2*dy-dz;
            for (int i = 0; i < dz; i++) {
                if (!clearForSmoothing(checker, x, y, z)) return false;
                int prevX = x, prevZ = z;
                if (e1>0){x+=sx;e1-=2*dz;} if(e2>0){y+=sy;e2-=2*dz;}
                e1+=2*dx; e2+=2*dy; z+=sz;
                if (diagonalStepBlocked(checker, prevX, prevZ, x, y, z)) return false;
            }
        }

        return clearForSmoothing(checker, x1, y1, z1);
    }

    private static boolean hasFlyLineOfSight(Node from, Node to, WalkabilityChecker checker) {
        int x0 = from.position.flooredX(), y0 = from.position.flooredY(), z0 = from.position.flooredZ();
        int x1 = to.position.flooredX(),   y1 = to.position.flooredY(),   z1 = to.position.flooredZ();

        int dx = Math.abs(x1-x0), dy = Math.abs(y1-y0), dz = Math.abs(z1-z0);
        int sx = x0<x1?1:-1, sy = y0<y1?1:-1, sz = z0<z1?1:-1;
        int x = x0, y = y0, z = z0;

        if (dx >= dy && dx >= dz) {
            int e1=2*dy-dx, e2=2*dz-dx;
            for (int i=0;i<dx;i++) {
                if (checker.isSolid(x,y,z)) return false;
                if(e1>0){y+=sy;e1-=2*dx;} if(e2>0){z+=sz;e2-=2*dx;}
                e1+=2*dy; e2+=2*dz; x+=sx;
            }
        } else if (dy >= dx && dy >= dz) {
            int e1=2*dx-dy, e2=2*dz-dy;
            for (int i=0;i<dy;i++) {
                if (checker.isSolid(x,y,z)) return false;
                if(e1>0){x+=sx;e1-=2*dy;} if(e2>0){z+=sz;e2-=2*dy;}
                e1+=2*dx; e2+=2*dz; y+=sy;
            }
        } else {
            int e1=2*dx-dz, e2=2*dy-dz;
            for (int i=0;i<dz;i++) {
                if (checker.isSolid(x,y,z)) return false;
                if(e1>0){x+=sx;e1-=2*dz;} if(e2>0){y+=sy;e2-=2*dz;}
                e1+=2*dx; e2+=2*dy; z+=sz;
            }
        }

        return !checker.isSolid(x1, y1, z1);
    }

    private static boolean clearForSmoothing(WalkabilityChecker checker, int x, int y, int z) {
        // isWalkable = passable(feet) + passable(head) + solid floor + not dangerous.
        // The floor check is required - without it the Bresenham line accepts floating
        // positions over air gaps, letting the smoother cut through walls incorrectly.
        return checker.isWalkable(x, y, z);
    }

    private static int computeAdaptiveSkipBudget(Node anchor, WalkabilityChecker checker) {
        int wallScore = computeWallScore(anchor, checker);

        if (wallScore <= OPEN_WALL_SCORE_MAX) return OPEN_SKIP_BUDGET;
        if (wallScore <= MID_WALL_SCORE_MAX) return MID_SKIP_BUDGET;
        return TIGHT_SKIP_BUDGET;
    }

    private static int computeWallScore(Node anchor, WalkabilityChecker checker) {
        if (checker == null) return 0;

        int x = anchor.position.flooredX();
        int y = anchor.position.flooredY();
        int z = anchor.position.flooredZ();

        int wallScore = 0;
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dz = {0, 0, 1, -1, 1, -1, 1, -1};
        for (int i = 0; i < dx.length; i++) {
            int nx = x + dx[i];
            int nz = z + dz[i];
            if (checker.isFullWall(nx, y, nz)) wallScore++;
            if (checker.isFullWall(nx, y + 1, nz)) wallScore++;
        }
        return wallScore;
    }

    private static boolean wouldSkipConstrainedCorner(List<Node> raw, int anchorIdx, int candidateIdx,
                                                      WalkabilityChecker checker) {
        for (int i = anchorIdx + 1; i < candidateIdx; i++) {
            if (isConstrainedCorner(raw, i, checker)) return true;
        }
        return false;
    }

    private static boolean isConstrainedCorner(List<Node> raw, int idx, WalkabilityChecker checker) {
        Node prev = raw.get(idx - 1);
        Node cur = raw.get(idx);
        Node next = raw.get(idx + 1);

        double inX = cur.position.centeredX() - prev.position.centeredX();
        double inZ = cur.position.centeredZ() - prev.position.centeredZ();
        double outX = next.position.centeredX() - cur.position.centeredX();
        double outZ = next.position.centeredZ() - cur.position.centeredZ();

        double inLen = Math.sqrt(inX * inX + inZ * inZ);
        double outLen = Math.sqrt(outX * outX + outZ * outZ);
        if (inLen < 1.0e-3 || outLen < 1.0e-3) return false;

        double dot = (inX / inLen) * (outX / outLen) + (inZ / inLen) * (outZ / outLen);
        double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        return angleDeg >= CONSTRAINED_CORNER_ANGLE_DEG && computeWallScore(cur, checker) > 0;
    }

    private static boolean diagonalStepBlocked(WalkabilityChecker checker,
                                               int fromX, int fromZ,
                                               int toX, int toY, int toZ) {
        if (fromX == toX || fromZ == toZ) return false;

        // For diagonal moves, ensure the swept side cells are open at feet/head.
        return sideCellBlocked(checker, toX, toY, fromZ)
                || sideCellBlocked(checker, fromX, toY, toZ);
    }

    private static boolean sideCellBlocked(WalkabilityChecker checker, int x, int y, int z) {
        return !checker.isPassable(x, y, z)
                || !checker.isPassable(x, y + 1, z)
                || checker.isFullWall(x, y, z)
                || checker.isFullWall(x, y + 1, z);
    }
}
