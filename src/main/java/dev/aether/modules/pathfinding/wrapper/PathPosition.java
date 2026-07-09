package dev.aether.modules.pathfinding.wrapper;

import net.minecraft.util.Mth;

public final class PathPosition {
    public final double x, y, z;

    public PathPosition(double x, double y, double z) {
        this.x = x; this.y = y; this.z = z;
    }

    public int flooredX() { return Mth.floor(x); }
    public int flooredY() { return Mth.floor(y); }
    public int flooredZ() { return Mth.floor(z); }

    public double centeredX() { return flooredX() + 0.5; }
    public double centeredY() { return flooredY() + 0.5; }
    public double centeredZ() { return flooredZ() + 0.5; }

    public double distanceSquared(PathPosition other) {
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return dx*dx + dy*dy + dz*dz;
    }

    public double distance(PathPosition other) {
        return Math.sqrt(distanceSquared(other));
    }

    public PathPosition setX(double x) { return new PathPosition(x, y, z); }
    public PathPosition setY(double y) { return new PathPosition(x, y, z); }
    public PathPosition setZ(double z) { return new PathPosition(x, y, z); }

    public PathPosition add(double x, double y, double z) {
        return new PathPosition(this.x + x, this.y + y, this.z + z);
    }
    public PathPosition add(PathVector v) { return add(v.x, v.y, v.z); }

    public PathPosition subtract(double x, double y, double z) {
        return new PathPosition(this.x - x, this.y - y, this.z - z);
    }
    public PathPosition subtract(PathVector v) { return subtract(v.x, v.y, v.z); }

    public PathVector toVector() { return new PathVector(x, y, z); }

    public PathPosition floor() {
        return new PathPosition(flooredX(), flooredY(), flooredZ());
    }

    public PathPosition mid() {
        return new PathPosition(flooredX() + 0.5, flooredY() + 0.5, flooredZ() + 0.5);
    }

    public PathPosition midPoint(PathPosition end) {
        return new PathPosition((x + end.x) / 2, (y + end.y) / 2, (z + end.z) / 2);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathPosition p)) return false;
        return flooredX() == p.flooredX() && flooredY() == p.flooredY() && flooredZ() == p.flooredZ();
    }

    @Override
    public int hashCode() {
        int result = flooredX();
        result = 31 * result + flooredY();
        result = 31 * result + flooredZ();
        return result;
    }

    @Override
    public String toString() {
        return "PathPosition{" + x + ", " + y + ", " + z + "}";
    }
}
