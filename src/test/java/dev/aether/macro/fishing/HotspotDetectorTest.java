package dev.aether.macro.fishing;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotspotDetectorTest {
    private static final Vec3 PLAYER = new Vec3(0.5, 64.0, 0.5);

    @BeforeAll
    static void bootstrap() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.setOut(out);
        System.setErr(err);
    }

    private static HotspotDetector.Hotspot at(int id, double x, double z) {
        return new HotspotDetector.Hotspot(id, new Vec3(x, 63.9, z), BlockPos.containing(x, 63.0, z), false);
    }

    @Test
    void onlyAStandNamedExactlyHotspotMarksOne() {
        assertTrue(HotspotDetector.isHotspotName("HOTSPOT"));
        assertTrue(HotspotDetector.isHotspotName("§d§lHOTSPOT "));
        assertFalse(HotspotDetector.isHotspotName("hotspot"));
        assertFalse(HotspotDetector.isHotspotName("+5 Sea Creature Chance"));
        assertFalse(HotspotDetector.isHotspotName(null));
    }

    @Test
    void theNearestHotspotInRangeIsPicked() {
        HotspotDetector.Hotspot far = at(1, 30.5, 0.5);
        HotspotDetector.Hotspot near = at(2, 10.5, 0.5);
        HotspotDetector.Hotspot outOfRange = at(3, 60.5, 0.5);

        assertSame(near, HotspotDetector.nearest(List.of(far, near, outOfRange), PLAYER, Set.of()));
        assertSame(far, HotspotDetector.nearest(List.of(far, near, outOfRange), PLAYER, Set.of(2)));
        assertNull(HotspotDetector.nearest(List.of(outOfRange), PLAYER, Set.of()));
        assertNull(HotspotDetector.nearest(List.of(), PLAYER, Set.of()));
    }

    @Test
    void aHotspotRightAtTheSelectRangeStillCounts() {
        HotspotDetector.Hotspot edge = new HotspotDetector.Hotspot(4,
                PLAYER.add(HotspotDetector.SELECT_RANGE, 0.0, 0.0), BlockPos.ZERO, false);
        assertSame(edge, HotspotDetector.nearest(List.of(edge), PLAYER, Set.of()));
    }

    @Test
    void aHotspotIsGoneWhenItsStandIsOrWhenItIsFarOff() {
        assertFalse(HotspotDetector.gone(false, 10.0));
        assertFalse(HotspotDetector.gone(false, HotspotDetector.GONE_RANGE));
        assertTrue(HotspotDetector.gone(false, HotspotDetector.GONE_RANGE + 0.01));
        assertTrue(HotspotDetector.gone(true, 5.0));
    }

    @Test
    void theSurfaceIsTheFirstLiquidUnderTheStand() {
        FakeCastLevel level = new FakeCastLevel()
                .fill(-4, 60, -4, 4, 61, 4, Blocks.WATER.defaultBlockState())
                .fill(-4, 62, -4, 4, 62, 4, Blocks.STONE.defaultBlockState())
                .fill(-4, 63, -4, 4, 63, 4, Blocks.LAVA.defaultBlockState());

        HotspotDetector.Hotspot hotspot = HotspotDetector.surfaceUnder(level, 7, new Vec3(0.3, 65.2, -1.4));

        assertEquals(7, hotspot.id());
        assertTrue(hotspot.lava());
        assertEquals(new BlockPos(0, 63, -2), hotspot.surfaceCell());
        assertEquals(63.0 + 8.0 / 9.0, hotspot.centre().y, 1.0e-6);
        assertEquals(0.3, hotspot.centre().x, 1.0e-9);
        assertEquals(-1.4, hotspot.centre().z, 1.0e-9);
        assertEquals(new BlockPos(0, 63, -2), BlockPos.containing(hotspot.aimPoint()));
    }

    @Test
    void aStandWithNoLiquidWithinFourBlocksMarksNothing() {
        FakeCastLevel level = new FakeCastLevel()
                .fill(-4, 60, -4, 4, 60, 4, Blocks.WATER.defaultBlockState());

        assertNull(HotspotDetector.surfaceUnder(level, 1, new Vec3(0.5, 65.5, 0.5)));
        HotspotDetector.Hotspot water = HotspotDetector.surfaceUnder(level, 1, new Vec3(0.5, 64.5, 0.5));
        assertFalse(water.lava());
        assertEquals(60, water.surfaceCell().getY());
    }

    // a pond with its surface cell at y 63 and a floor block at the given height in each column
    private static HotspotDetector.Column pond(int floorY, int shallowX, int shallowFloorY) {
        return new HotspotDetector.Column() {
            private int floorAt(BlockPos pos) {
                return pos.getX() == shallowX ? shallowFloorY : floorY;
            }

            @Override
            public boolean water(BlockPos pos) {
                return pos.getY() > floorAt(pos) && pos.getY() <= 63;
            }

            @Override
            public boolean solid(BlockPos pos) {
                return pos.getY() <= floorAt(pos);
            }
        };
    }

    @Test
    void theCentreSpotIsTheFloorRightUnderTheNametag() {
        HotspotDetector.Hotspot hotspot = new HotspotDetector.Hotspot(1, new Vec3(0.5, 63.9, 0.5),
                new BlockPos(0, 63, 0), false);

        assertEquals(new BlockPos(0, 61, 0), HotspotDetector.centreFeet(pond(60, 99, 0), hotspot));
    }

    @Test
    void aColumnTooShallowToStandUnderIsSkippedForTheOneBesideIt() {
        HotspotDetector.Hotspot hotspot = new HotspotDetector.Hotspot(1, new Vec3(0.4, 63.9, 0.5),
                new BlockPos(0, 63, 0), false);

        // the middle column only has one block of water over its floor, so the head would stick out
        BlockPos feet = HotspotDetector.centreFeet(pond(60, 0, 62), hotspot);
        assertEquals(new BlockPos(-1, 61, 0), feet);
    }

    @Test
    void lavaAndShallowWaterHaveNoCentreSpot() {
        HotspotDetector.Hotspot lava = new HotspotDetector.Hotspot(1, new Vec3(0.5, 63.9, 0.5),
                new BlockPos(0, 63, 0), true);
        HotspotDetector.Hotspot water = new HotspotDetector.Hotspot(2, new Vec3(0.5, 63.9, 0.5),
                new BlockPos(0, 63, 0), false);

        assertNull(HotspotDetector.centreFeet(pond(60, 99, 0), lava));
        assertNull(HotspotDetector.centreFeet(pond(62, 99, 0), water));
    }
}
