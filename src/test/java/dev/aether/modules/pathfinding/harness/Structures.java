package dev.aether.modules.pathfinding.harness;

// tight builds that reproduce the kind of geometry the fly route struggles with in the Garden
public final class Structures {
    private Structures() {
    }

    public static final int GROUND_Y = 63;

    // glass house: 4 blocks of headroom, one 1x2 door, 1-wide planting aisles,
    // roof beams and two pillars to break up the open span above the beds
    public static BlockWorld greenhouse() {
        BlockWorld world = new BlockWorld().ground(GROUND_Y);
        world.shell(0, 64, 0, 14, 69, 10);
        world.carve(7, 65, 0, 7, 66, 0);
        for (int z = 2; z <= 8; z += 2) {
            world.fill(1, 65, z, 13, 65, z);
        }
        for (int x = 3; x <= 11; x += 4) {
            world.fill(x, 68, 1, x, 68, 9);
        }
        world.fill(4, 65, 2, 4, 67, 2);
        world.fill(10, 65, 8, 10, 67, 8);
        world.set(5, 67, 5).set(9, 67, 5);
        return world;
    }

    // two rooms joined by a doorway that does not line up with either room's centre
    public static BlockWorld doorwayHop() {
        BlockWorld world = new BlockWorld().ground(GROUND_Y);
        world.shell(0, 64, 0, 12, 70, 8);
        world.shell(0, 64, 8, 12, 70, 18);
        world.carve(1, 65, 1, 11, 69, 7);
        world.carve(1, 65, 9, 11, 69, 17);
        world.fill(1, 65, 8, 11, 69, 8);
        world.carve(3, 65, 8, 3, 66, 8);
        world.fill(6, 65, 12, 6, 69, 12);
        world.fill(1, 68, 1, 11, 68, 5);
        return world;
    }

    // a 1-wide L-bend corridor with a step up halfway along it
    public static BlockWorld narrowBend() {
        BlockWorld world = new BlockWorld().ground(GROUND_Y);
        world.fill(-2, 64, -2, 20, 72, 20);
        world.carve(2, 65, 2, 2, 66, 14);
        world.carve(2, 67, 10, 16, 68, 10);
        world.carve(2, 65, 10, 2, 68, 10);
        return world;
    }

    // open sky with a long wall that has a single gap in it
    public static BlockWorld fieldWall() {
        BlockWorld world = new BlockWorld().ground(GROUND_Y);
        world.fill(-20, 64, 10, 20, 78, 10);
        world.carve(3, 66, 10, 4, 68, 10);
        return world;
    }
}
