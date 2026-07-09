package dev.aether.modules.pathfinding.provider;

public interface NavigationPoint {
    boolean isTraversable();
    boolean hasFloor();
    double getFloorLevel();
    boolean isClimbable();
    boolean isLiquid();
}
