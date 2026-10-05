package dev.aether.ui.orbit;

import dev.aether.renderer.McIcons;
import dev.aether.renderer.McTextures;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.plot.PlotPickerModel;
import dev.aether.ui.gui.plot.PlotSlots;
import dev.aether.ui.settings.PlotToken;
import dev.aether.ui.util.Fonts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

// the garden as a little block model: 24 plots and the barn, drawn orthographically from any yaw with the game's
// own block textures, so the same code paints the row thumbnail and the big plot screen
final class PlotDiorama {
    private static final float PLOT = 0.86f;
    private static final float BASE = 0.18f;
    private static final float LIFT = 0.24f;
    private static final float SHADE_TOP = 1f;
    private static final float SHADE_LEFT = 0.78f;
    private static final float SHADE_RIGHT = 0.6f;

    // the screen outline of one plot's top face, for picking; last drawn is on top
    record Pick(int plot, float[] polygon) {
        boolean contains(float x, float y) {
            boolean inside = false;
            int n = polygon.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                float xi = polygon[i * 2], yi = polygon[i * 2 + 1];
                float xj = polygon[j * 2], yj = polygon[j * 2 + 1];
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
            }
            return inside;
        }
    }

    // cx/cy is the screen point the barn sits on, scale is pixels per plot, yaw and pitch in degrees
    record View(float cx, float cy, float scale, float yaw, float pitch) {
        float[] project(double x, double y, double z) {
            double yaw = Math.toRadians(this.yaw);
            double pitch = Math.toRadians(this.pitch);
            double rx = x * Math.cos(yaw) - z * Math.sin(yaw);
            double rz = x * Math.sin(yaw) + z * Math.cos(yaw);
            float sx = (float) (cx + rx * scale);
            float sy = (float) (cy + (rz * Math.sin(pitch) - y * Math.cos(pitch)) * scale);
            return new float[]{sx, sy};
        }

        double depth(double x, double y, double z) {
            double yaw = Math.toRadians(this.yaw);
            double pitch = Math.toRadians(this.pitch);
            double rz = x * Math.sin(yaw) + z * Math.cos(yaw);
            return rz * Math.cos(pitch) + y * Math.sin(pitch);
        }
    }

    private PlotDiorama() {
    }

    // draws every plot far to near; lift is 0..1 per plot number (index 0 = barn) for the selection rise
    static List<Pick> draw(NVGRenderer nvg, View view, IntFunction<PlotPickerModel.PlotLook> looks, float[] lift,
                           int hover, float time, boolean labels, int accent, float alpha) {
        List<Integer> order = new ArrayList<>();
        for (int plot = 0; plot <= PlotToken.MAX_PLOT; plot++) order.add(plot);
        order.sort(Comparator.comparingDouble(plot -> view.depth(x(plot), 0, z(plot))));
        List<Pick> picks = new ArrayList<>();
        for (int plot : order) {
            PlotPickerModel.PlotLook look = looks.apply(plot);
            float rise = lift == null ? 0f : lift[plot];
            // solid blocks read wrong when see-through, so the picker's dimming darkens them instead
            float dim = look == null || look.alpha() >= 1f ? 1f : 0.55f;
            picks.add(drawPlot(nvg, view, plot, look, rise, plot == hover, time, labels, accent, alpha, dim));
        }
        return picks;
    }

    private static Pick drawPlot(NVGRenderer nvg, View view, int plot, PlotPickerModel.PlotLook look, float rise,
                                 boolean hovered, float time, boolean labels, int accent, float alpha, float dim) {
        boolean barn = plot == PlotToken.BARN;
        String item = look == null ? null : look.itemId();
        float half = (barn ? 0.8f : PLOT) / 2f;
        float height = barn ? 0.62f : BASE + rise * LIFT + (glassy(item) ? 0.22f : 0f);
        double cx = x(plot), cz = z(plot);
        double x0 = cx - half, x1 = cx + half, z0 = cz - half, z1 = cz + half;

        String side = barn ? "minecraft:textures/block/dark_oak_planks.png"
                : glassy(item) ? "minecraft:textures/block/white_stained_glass.png" : "minecraft:textures/block/dirt.png";
        String top = barn ? "minecraft:textures/block/dark_oak_planks.png" : topTexture(item);
        int topTint = barn ? 0xFFFFFFFF : topTint(item);

        // the two vertical faces turned toward the viewer, then the top
        double yaw = Math.toRadians(view.yaw());
        boolean southVisible = Math.cos(yaw) > 0;
        boolean eastVisible = Math.sin(yaw) < 0;
        double zFace = southVisible ? z1 : z0;
        double xFace = eastVisible ? x1 : x0;
        face(nvg, view, new double[]{x0, height, zFace}, new double[]{x1, height, zFace}, new double[]{x0, 0, zFace},
                side, shade(0xFFFFFFFF, SHADE_LEFT * dim), alpha);
        face(nvg, view, new double[]{xFace, height, z0}, new double[]{xFace, height, z1}, new double[]{xFace, 0, z0},
                side, shade(0xFFFFFFFF, SHADE_RIGHT * dim), alpha);
        float[] tl = view.project(x0, height, z0), tr = view.project(x1, height, z0);
        float[] br = view.project(x1, height, z1), bl = view.project(x0, height, z1);
        int topColor = look != null && look.marked() ? Argb.mix(topTint, accent, 0.28f) : topTint;
        face(nvg, view, new double[]{x0, height, z0}, new double[]{x1, height, z0}, new double[]{x0, height, z1},
                top, shade(topColor, SHADE_TOP * dim), alpha);
        float[] polygon = {tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]};

        if (look != null && look.marked()) {
            outline(nvg, polygon, 2.2f, Argb.multiplyAlpha(accent, alpha));
        } else if (hovered) {
            outline(nvg, polygon, 1.6f, Argb.multiplyAlpha(0xCCFFFFFF, alpha));
        }

        float[] center = view.project(cx, height, cz);
        float size = view.scale() * 0.34f;
        if (!barn && cropItem(item)) {
            float[] a = view.project(cx - 0.2, height, cz - 0.15);
            float[] b = view.project(cx + 0.2, height, cz + 0.15);
            icon(nvg, item, a[0], a[1] - size * 0.8f, size, alpha);
            icon(nvg, item, b[0], b[1] - size * 0.8f, size, alpha);
        }
        if (look != null && look.pests() != 0) {
            float bob = (float) Math.sin(time * 3 + plot) * view.scale() * 0.04f;
            float py = center[1] - view.scale() * 0.62f + bob;
            icon(nvg, "minecraft:silverfish_spawn_egg", center[0], py, size * 1.1f, alpha);
            if (labels && look.pests() > 0) {
                badge(nvg, center[0] + size * 0.55f, py - size * 0.55f, Integer.toString(look.pests()),
                        Argb.multiplyAlpha(0xFFE5484D, alpha), alpha);
            }
        }
        if (look != null && look.current()) {
            float py = center[1] - view.scale() * (look.pests() != 0 ? 1.05f : 0.6f);
            icon(nvg, "minecraft:player_head", center[0], py, size, alpha);
        }
        if (labels && !barn) {
            String text = look != null && look.order() > 0 ? "#" + look.order() : Integer.toString(plot);
            float fs = Math.max(7f, view.scale() * 0.17f);
            float tw = nvg.textWidth(Fonts.UI_BOLD, text, fs);
            float tx = center[0] - tw / 2f;
            float ty = center[1] + view.scale() * 0.08f;
            nvg.text(Fonts.UI_BOLD, text, tx + 1f, ty + 1f, fs, Argb.multiplyAlpha(0xAA000000, alpha));
            nvg.text(Fonts.UI_BOLD, text, tx, ty, fs, Argb.multiplyAlpha(0xFFFFFFFF, alpha));
        }
        return new Pick(plot, polygon);
    }

    // maps the unit square onto the parallelogram p0 -> p1 (u) and p0 -> p3 (v)
    private static void face(NVGRenderer nvg, View view, double[] p0, double[] p1, double[] p3, String texture,
                             int tint, float alpha) {
        McTextures.Texture tex = McTextures.get(texture);
        float[] a = view.project(p0[0], p0[1], p0[2]);
        float[] b = view.project(p1[0], p1[1], p1[2]);
        float[] d = view.project(p3[0], p3[1], p3[2]);
        int color = Argb.multiplyAlpha(tint, alpha);
        nvg.save();
        nvg.transform(b[0] - a[0], b[1] - a[1], d[0] - a[0], d[1] - a[1], a[0], a[1]);
        if (tex.missing()) {
            nvg.rect(0f, 0f, 1f, 1f, color);
        } else {
            nvg.imageRegion(tex.handle(), tex.width(), tex.height(), 0f, 0f, tex.width(), Math.min(tex.width(), tex.height()),
                    0f, 0f, 1f, 1f, color);
        }
        nvg.restore();
    }

    private static void outline(NVGRenderer nvg, float[] p, float width, int color) {
        nvg.beginPath();
        nvg.moveTo(p[0], p[1]);
        nvg.lineTo(p[2], p[3]);
        nvg.lineTo(p[4], p[5]);
        nvg.lineTo(p[6], p[7]);
        nvg.closePath();
        nvg.strokePath(width, color);
    }

    private static void icon(NVGRenderer nvg, String item, float cx, float cy, float size, float alpha) {
        nvg.mcIcon(McIcons.of(item), cx - size / 2f, cy - size / 2f, size, Argb.multiplyAlpha(0xFFFFFFFF, alpha));
    }

    private static void badge(NVGRenderer nvg, float cx, float cy, String text, int fill, float alpha) {
        float fs = 7.5f;
        float w = Math.max(11f, nvg.textWidth(Fonts.UI_BOLD, text, fs) + 6f);
        nvg.roundedRect(cx - w / 2f, cy - 5.5f, w, 11f, 5.5f, fill);
        nvg.text(Fonts.UI_BOLD, text, cx - nvg.textWidth(Fonts.UI_BOLD, text, fs) / 2f, cy - 4f, fs,
                Argb.multiplyAlpha(0xFFFFFFFF, alpha));
    }

    private static double x(int plot) {
        return PlotSlots.mapColumn(plot) - 2;
    }

    private static double z(int plot) {
        return PlotSlots.mapRow(plot) - 2;
    }

    private static boolean glassy(String item) {
        return item != null && item.endsWith("white_stained_glass");
    }

    private static boolean cropItem(String item) {
        if (item == null) return false;
        return !item.endsWith("stained_glass_pane") && !item.endsWith("stained_glass") && !item.endsWith("oak_button")
                && !item.endsWith("planks") && !item.endsWith("grass_block");
    }

    private static String topTexture(String item) {
        if (item == null) return "minecraft:textures/block/grass_block_top.png";
        if (item.endsWith("red_stained_glass_pane")) return "minecraft:textures/block/stone.png";
        if (item.endsWith("orange_stained_glass_pane") || item.endsWith("oak_button")) {
            return "minecraft:textures/block/coarse_dirt.png";
        }
        if (glassy(item)) return "minecraft:textures/block/white_stained_glass.png";
        if (cropItem(item)) return "minecraft:textures/block/farmland_moist.png";
        return "minecraft:textures/block/grass_block_top.png";
    }

    private static int topTint(String item) {
        String texture = topTexture(item);
        return texture.endsWith("grass_block_top.png") ? 0xFF7CBD6B : 0xFFFFFFFF;
    }

    private static int shade(int argb, float k) {
        int r = Math.round(((argb >> 16) & 255) * k);
        int g = Math.round(((argb >> 8) & 255) * k);
        int b = Math.round((argb & 255) * k);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
