package dev.aether.ui.orbit;

// a top-down picture of a plot that was never loaded, drawn from what the plot menu says grows there: rows of the
// crop over its soil with water lanes, coarse dirt for plots still being cleared, stone for locked ones
final class PlotSketch {
    private record Look(int soil, int crop, int fruit, boolean lanes) {
    }

    private PlotSketch() {
    }

    static int[] draw(int plot, String item, int size) {
        String id = item == null ? "" : item.replace("minecraft:", "");
        int[] out = new int[size * size];
        if (plot == 0) {
            // the barn's roof, in rows of planks
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) out[y * size + x] = shade(0xFF4A3424, (y % 4 == 3 ? 0.75f : 0.95f) + noise(plot, x, y) * 0.1f);
            }
            edge(out, size);
            return out;
        }
        if (id.endsWith("red_stained_glass_pane") || id.endsWith("gray_stained_glass_pane")
                || id.endsWith("black_stained_glass_pane")) {
            fill(out, size, plot, 0xFF7C7C7C, 0.10f);
            return out;
        }
        if (id.endsWith("orange_stained_glass_pane") || id.endsWith("oak_button")) {
            fill(out, size, plot, 0xFF77553B, 0.14f);
            return out;
        }
        Look look = look(id);
        if (look == null) {
            fill(out, size, plot, 0xFF6FA550, 0.10f);
            return out;
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float n = noise(plot, x, y);
                int c;
                if (look.lanes() && x % 16 == 7) {
                    c = 0xFF3F76E4;
                } else if (y % 3 == 2) {
                    c = shade(look.soil(), 0.9f + n * 0.1f);
                } else if (look.fruit() != 0 && noise(plot + 7, x, y) > 0.86f) {
                    c = look.fruit();
                } else {
                    c = shade(look.crop(), 0.86f + n * 0.22f);
                }
                out[y * size + x] = c;
            }
        }
        if (id.endsWith("white_stained_glass")) {
            for (int i = 0; i < out.length; i++) out[i] = mix(out[i], 0xFFE8F0F2, 0.35f);
        }
        edge(out, size);
        return out;
    }

    private static Look look(String id) {
        return switch (id) {
            case "wheat" -> new Look(0xFF5B3A24, 0xFFDCC25C, 0, true);
            case "carrot" -> new Look(0xFF5B3A24, 0xFF4E9A2E, 0xFFE88A1E, true);
            case "potato" -> new Look(0xFF5B3A24, 0xFF5FA83A, 0xFFC9A55C, true);
            case "pumpkin" -> new Look(0xFF5B3A24, 0xFF4F8A2E, 0xFFE3901D, true);
            case "melon", "melon_slice" -> new Look(0xFF5B3A24, 0xFF4F8A2E, 0xFF8DB534, true);
            case "sugar_cane" -> new Look(0xFFDBCFA0, 0xFF8BC34A, 0, true);
            case "cactus" -> new Look(0xFFDBCFA0, 0xFF4F7A33, 0, false);
            case "cocoa_beans" -> new Look(0xFF5C4527, 0xFF6B5130, 0xFFA0602E, false);
            case "nether_wart" -> new Look(0xFF4E3B30, 0xFF9E2A2A, 0, false);
            case "red_mushroom" -> new Look(0xFF6E6070, 0xFFC83A32, 0xFFEFE6E0, false);
            case "brown_mushroom" -> new Look(0xFF6E6070, 0xFF9A7454, 0, false);
            case "sunflower" -> new Look(0xFF5B3A24, 0xFF4F8A2E, 0xFFF2CF2E, true);
            case "rose_bush", "wild_rose" -> new Look(0xFF5B3A24, 0xFF3F7A33, 0xFFB52A3A, true);
            case "white_stained_glass" -> new Look(0xFF5B3A24, 0xFF5FA83A, 0, true);
            default -> id.isEmpty() || id.endsWith("glass_pane") || id.equals("grass_block") ? null
                    : new Look(0xFF5B3A24, 0xFF5FA83A, 0, true);
        };
    }

    private static void fill(int[] out, int size, int plot, int color, float grain) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) out[y * size + x] = shade(color, 1f - grain + noise(plot, x, y) * grain * 2f);
        }
        edge(out, size);
    }

    // a darker rim so neighbouring plots read apart, like the paths between them in game
    private static void edge(int[] out, int size) {
        for (int i = 0; i < size; i++) {
            out[i] = shade(out[i], 0.7f);
            out[(size - 1) * size + i] = shade(out[(size - 1) * size + i], 0.7f);
            out[i * size] = shade(out[i * size], 0.7f);
            out[i * size + size - 1] = shade(out[i * size + size - 1], 0.7f);
        }
    }

    private static float noise(int seed, int x, int y) {
        long h = x * 73856093L ^ y * 19349663L ^ seed * 83492791L;
        h = (h ^ (h >>> 13)) * 0x5bd1e995L;
        return ((h ^ (h >>> 15)) & 0xFFFF) / 65535f;
    }

    private static int shade(int argb, float k) {
        int r = Math.min(255, Math.round(((argb >> 16) & 255) * k));
        int g = Math.min(255, Math.round(((argb >> 8) & 255) * k));
        int b = Math.min(255, Math.round((argb & 255) * k));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
