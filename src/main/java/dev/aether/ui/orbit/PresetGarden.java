package dev.aether.ui.orbit;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

// the menu's own little garden, built from real blocks around the player: a path at your feet, crop fields split by
// water lanes on both sides, a pumpkin and melon patch behind, a barn and a few trees. always complete, never loaded
final class PresetGarden implements SceneClone.Source {
    private static final int RADIUS = 40;
    private static final int LOW = -2;
    private static final int HIGH = 10;

    private final int ox, oy, oz;
    private final int cos, sin;
    private final int size = RADIUS * 2 + 1;
    private final BlockState[][] columns = new BlockState[size * size][];
    private final int[] tops = new int[size * size];

    // yaw is snapped to quarter turns, so the farm's +z (ahead of the player) lines up with the ring's front
    PresetGarden(int ox, int oy, int oz, float yaw) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        int quarter = Math.floorMod(Math.round(yaw / 90f), 4);
        this.cos = new int[]{1, 0, -1, 0}[quarter];
        this.sin = new int[]{0, 1, 0, -1}[quarter];
        for (int i = 0; i < columns.length; i++) columns[i] = new BlockState[HIGH - LOW + 1];
        build();
        for (int i = 0; i < columns.length; i++) {
            tops[i] = Integer.MIN_VALUE;
            for (int y = HIGH; y >= LOW; y--) {
                BlockState s = columns[i][y - LOW];
                if (s != null && !s.isAir()) {
                    tops[i] = oy + y;
                    break;
                }
            }
        }
    }

    // -- the layout, in blocks around the player's feet: +z ahead, ground at y -1 --------------------------------

    private void build() {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                set(x, -2, z, dirt);
                set(x, -1, z, grass);
                // a little meadow on the untouched grass
                float h = hash(x, z);
                if (h < 0.06f) set(x, 0, z, Blocks.SHORT_GRASS.defaultBlockState());
                else if (h < 0.07f) set(x, 0, z, Blocks.POPPY.defaultBlockState());
                else if (h < 0.08f) set(x, 0, z, Blocks.DANDELION.defaultBlockState());
            }
        }
        field(3, 34, -8, 13, crop(Blocks.WHEAT));
        field(3, 34, 15, 36, crop(Blocks.CARROTS));
        field(-34, -3, -8, 13, crop(Blocks.POTATOES));
        canes(-34, -3, 15, 36);
        patch(-22, 22, -34, -14);
        path();
        barn(-29, 22);
        tree(30, -24);
        tree(-33, -12);
        tree(36, 6);
        tree(-14, 37);
        tree(18, 38);
        hay(-17, 21);
        hay(-17, 23);
        hay(-16, 22);
        set(-18, 0, 31, Blocks.COMPOSTER.defaultBlockState());
    }

    private static BlockState crop(Block block) {
        return block instanceof CropBlock crop ? crop.getStateForAge(crop.getMaxAge()) : block.defaultBlockState();
    }

    // farmland rows of one crop, with a water lane every nine blocks the way garden farms are laid out
    private void field(int x0, int x1, int z0, int z1, BlockState crop) {
        BlockState soil = farmland();
        for (int x = x0; x <= x1; x++) {
            boolean lane = Math.floorMod(Math.abs(x) - 3, 9) == 4;
            for (int z = z0; z <= z1; z++) {
                clear(x, z);
                if (lane) {
                    set(x, -1, z, Blocks.WATER.defaultBlockState());
                } else {
                    set(x, -1, z, soil);
                    set(x, 0, z, crop);
                }
            }
        }
    }

    private void canes(int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            boolean lane = Math.floorMod(Math.abs(x) - 3, 9) == 4;
            for (int z = z0; z <= z1; z++) {
                clear(x, z);
                if (lane) {
                    set(x, -1, z, Blocks.WATER.defaultBlockState());
                } else {
                    set(x, -1, z, Blocks.SAND.defaultBlockState());
                    set(x, 0, z, Blocks.SUGAR_CANE.defaultBlockState());
                    if (hash(x, z + 99) > 0.3f) set(x, 1, z, Blocks.SUGAR_CANE.defaultBlockState());
                }
            }
        }
    }

    private void patch(int x0, int x1, int z0, int z1) {
        BlockState soil = farmland();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                clear(x, z);
                set(x, -1, z, soil);
                if (Math.floorMod(z, 3) != 1) continue;
                float h = hash(x * 3, z);
                if (h < 0.45f) set(x, 0, z, Blocks.PUMPKIN.defaultBlockState());
                else if (h < 0.85f) set(x, 0, z, Blocks.MELON.defaultBlockState());
            }
        }
    }

    // a dirt path you stand on, running ahead to the fields and across between them
    private void path() {
        BlockState path = Blocks.DIRT_PATH.defaultBlockState();
        for (int z = -12; z <= 38; z++) {
            for (int x = -1; x <= 1; x++) {
                clear(x, z);
                set(x, -1, z, path);
            }
        }
        for (int x = -38; x <= 38; x++) {
            for (int z = 13; z <= 15; z++) {
                clear(x, z);
                set(x, -1, z, path);
            }
        }
    }

    // a dark oak barn with a stepped spruce roof, its long side facing the player
    private void barn(int x0, int z0) {
        int w = 11, d = 9;
        BlockState wall = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState post = Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState roof = Blocks.SPRUCE_PLANKS.defaultBlockState();
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                clear(x, z);
                set(x, -1, z, Blocks.DIRT_PATH.defaultBlockState());
                boolean edge = x == x0 || x == x0 + w - 1 || z == z0 || z == z0 + d - 1;
                if (!edge) continue;
                boolean corner = (x == x0 || x == x0 + w - 1) && (z == z0 || z == z0 + d - 1);
                boolean door = z == z0 && Math.abs(x - (x0 + w / 2)) <= 1;
                for (int y = 0; y <= 4; y++) {
                    if (door && y <= 2) continue;
                    set(x, y, z, corner ? post : wall);
                }
            }
        }
        for (int k = 0; k <= 4; k++) {
            for (int x = x0 - 1 + k; x <= x0 + w - k; x++) {
                for (int z = z0 - 1; z <= z0 + d; z++) set(x, 5 + k, z, roof);
            }
        }
    }

    private void tree(int x, int z) {
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        for (int y = 3; y <= 6; y++) {
            int r = y >= 6 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r && (y == 6 || hash(x + dx, z + dz + y) < 0.5f)) continue;
                    set(x + dx, y, z + dz, leaves);
                }
            }
        }
        for (int y = 0; y <= 4; y++) set(x, y, z, log);
    }

    private void hay(int x, int z) {
        set(x, 0, z, Blocks.HAY_BLOCK.defaultBlockState());
    }

    private static BlockState farmland() {
        BlockState soil = Blocks.FARMLAND.defaultBlockState();
        try {
            return soil.setValue(FarmlandBlock.MOISTURE, 7);
        } catch (IllegalArgumentException e) {
            return soil;
        }
    }

    // -- storage ----------------------------------------------------------------------------------------------

    private void set(int x, int y, int z, BlockState state) {
        if (Math.abs(x) > RADIUS || Math.abs(z) > RADIUS || y < LOW || y > HIGH) return;
        columns[(z + RADIUS) * size + x + RADIUS][y - LOW] = state;
    }

    // drops anything standing on the ground, so a field or path never keeps the meadow's flowers
    private void clear(int x, int z) {
        if (Math.abs(x) > RADIUS || Math.abs(z) > RADIUS) return;
        BlockState[] column = columns[(z + RADIUS) * size + x + RADIUS];
        for (int y = 0; y <= HIGH; y++) column[y - LOW] = null;
    }

    private static float hash(int x, int z) {
        long h = x * 73856093L ^ z * 19349663L;
        h = (h ^ (h >>> 13)) * 0x5bd1e995L;
        return ((h ^ (h >>> 15)) & 0xFFFF) / 65535f;
    }

    // -- source -----------------------------------------------------------------------------------------------

    @Override
    public int top(int x, int z) {
        int wx = x - ox, wz = z - oz;
        int dx = wx * cos + wz * sin, dz = -wx * sin + wz * cos;
        if (Math.abs(dx) > RADIUS || Math.abs(dz) > RADIUS) return Integer.MIN_VALUE;
        return tops[(dz + RADIUS) * size + dx + RADIUS];
    }

    @Override
    public BlockState state(int x, int y, int z) {
        int wx = x - ox, wz = z - oz, ly = y - oy;
        int dx = wx * cos + wz * sin, dz = -wx * sin + wz * cos;
        if (Math.abs(dx) > RADIUS || Math.abs(dz) > RADIUS || ly < LOW) return Blocks.DIRT.defaultBlockState();
        if (ly > HIGH) return Blocks.AIR.defaultBlockState();
        BlockState s = columns[(dz + RADIUS) * size + dx + RADIUS][ly - LOW];
        return s == null ? Blocks.AIR.defaultBlockState() : s;
    }

    @Override
    public int tint(BlockTintSource tint, BlockState state, BlockPos pos) {
        return tint.color(state);
    }
}
