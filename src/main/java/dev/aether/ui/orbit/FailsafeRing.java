package dev.aether.ui.orbit;

import dev.aether.renderer.McIcons;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.util.Fonts;
import org.joml.Vector3d;

import java.util.List;

// the failsafes as a perimeter around the player: one glowing node per failsafe and a ring on the ground,
// shown while the safety panel is in front; rendering only
final class FailsafeRing implements AutoCloseable {
    private static final double RADIUS = 2.6;
    private static final float NODE_W = 96f;
    private static final float NODE_H = 120f;

    private final OrbitSpring visible = new OrbitSpring(0f, 60f, 13f);
    private final OrbitSpring[] lift = new OrbitSpring[16];
    private final PanelSurface[] nodes = new PanelSurface[16];
    private final PanelSurface ground = new PanelSurface();

    FailsafeRing() {
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = new PanelSurface();
            lift[i] = new OrbitSpring(0f, 120f, 12f);
        }
    }

    void step(float dt, boolean show) {
        visible.t = show ? 1f : 0f;
        visible.step(dt);
        for (OrbitSpring s : lift) s.step(dt);
    }

    // failsafes are {raw name, item id, "1" armed / "0" off, display name}
    void appendQuads(List<OrbitWorldRenderer.Quad> out, List<String[]> failsafes, String hovered, Vector3d feet,
                     OrbitLayout.Camera cam, float time) {
        float v = visible.x;
        if (v < 0.01f || failsafes.isEmpty()) return;
        Palette p = Palette.fromTheme();
        ground.render(256f, 256f, 2f, nvg -> drawGround(nvg, p, failsafes, v));
        double r = RADIUS * (0.85 + 0.15 * v);
        out.add(new OrbitWorldRenderer.Quad(new Vector3d(feet).add(-r, 0.03, -r), new Vector3d(feet).add(r, 0.03, -r),
                new Vector3d(feet).add(r, 0.03, r), new Vector3d(feet).add(-r, 0.03, r), ground.texture(), v * 0.9f, 0f));
        int n = Math.min(failsafes.size(), nodes.length);
        for (int i = 0; i < n; i++) {
            String[] fs = failsafes.get(i);
            boolean armed = "1".equals(fs[2]);
            boolean hot = fs[0].equals(hovered);
            lift[i].t = hot ? 1f : 0f;
            double angle = Math.PI * 2 * i / n + time * 0.12;
            double bob = Math.sin(time * 1.6 + i) * 0.06;
            Vector3d center = new Vector3d(feet).add(Math.cos(angle) * r, 0.95 + bob + lift[i].x * 0.35, Math.sin(angle) * r);
            int index = i;
            nodes[i].render(NODE_W, NODE_H, 2f, nvg -> drawNode(nvg, p, fs, armed, lift[index].x, time + index));
            double w = 0.55 + lift[i].x * 0.15;
            double h = w * NODE_H / NODE_W;
            Vector3d right = new Vector3d(cam.right()).mul(w / 2);
            Vector3d up = new Vector3d(cam.up()).mul(h / 2);
            out.add(new OrbitWorldRenderer.Quad(new Vector3d(center).sub(right).add(up), new Vector3d(center).add(right).add(up),
                    new Vector3d(center).add(right).sub(up), new Vector3d(center).sub(right).sub(up), nodes[i].texture(),
                    v, armed ? 0f : 0.25f));
        }
    }

    private static void drawGround(NVGRenderer nvg, Palette p, List<String[]> failsafes, float v) {
        int armed = 0;
        for (String[] fs : failsafes) if ("1".equals(fs[2])) armed++;
        int color = armed == failsafes.size() ? p.success() : Argb.mix(p.success(), p.warning(), 0.6f);
        nvg.circleOutline(128f, 128f, 118f, 3f, Argb.withAlpha(color, 0.75f * v));
        nvg.circleOutline(128f, 128f, 110f, 1.2f, Argb.withAlpha(color, 0.35f * v));
        for (int i = 0; i < 48; i++) {
            double a = Math.PI * 2 * i / 48;
            float x0 = 128f + (float) Math.cos(a) * 112f, y0 = 128f + (float) Math.sin(a) * 112f;
            float x1 = 128f + (float) Math.cos(a) * 118f, y1 = 128f + (float) Math.sin(a) * 118f;
            nvg.line(x0, y0, x1, y1, 1.2f, Argb.withAlpha(color, 0.5f * v));
        }
    }

    private static void drawNode(NVGRenderer nvg, Palette p, String[] fs, boolean armed, float hot, float time) {
        float cx = NODE_W / 2f;
        float cy = 44f;
        int color = armed ? p.success() : Argb.mix(p.border(), p.text(), 0.2f);
        float pulse = armed ? 0.75f + 0.25f * (float) Math.sin(time * 2.2) : 0.4f;
        nvg.glowCircle(cx, cy, 30f, Argb.withAlpha(color, 0.55f * pulse), 2f);
        nvg.circle(cx, cy, 26f, Argb.withAlpha(0xFF0B0F14, 0.82f));
        nvg.circleOutline(cx, cy, 26f, 2.5f, Argb.withAlpha(color, 0.95f));
        nvg.mcIcon(McIcons.of(fs[1]), cx - 16f, cy - 16f, 32f, armed ? 0xFFFFFFFF : 0xFF8A8A8A);
        if (hot > 0.05f) {
            String name = fs[3];
            float size = 12f;
            float tw = nvg.textWidth(Fonts.UI_SEMIBOLD, name, size);
            float bw = Math.min(NODE_W - 4f, tw + 14f);
            int a = Math.round(255 * Math.min(1f, hot));
            nvg.roundedRect(cx - bw / 2f, 86f, bw, 22f, 7f, Argb.withAlpha(0xFF0B0F14, 0.88f * hot));
            nvg.text(Fonts.UI_SEMIBOLD, name, cx - Math.min(tw, bw - 14f) / 2f, 90f, size, (a << 24) | (p.text() & 0xFFFFFF));
        }
    }

    @Override
    public void close() {
        for (PanelSurface s : nodes) s.close();
        ground.close();
    }
}
