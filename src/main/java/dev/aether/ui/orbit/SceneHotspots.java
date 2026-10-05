package dev.aether.ui.orbit;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Palette;
import dev.aether.util.AetherLang;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

// the parts of the preset farm you can point at: the one under the cursor glows with its name over it, and a click
// opens what it stands for. the player figure is one too, and waves back
final class SceneHotspots implements AutoCloseable {
    record Hotspot(String label, Vector3d min, Vector3d max, String target) {
        Vector3d top() {
            return new Vector3d((min.x + max.x) / 2, max.y + 0.6, (min.z + max.z) / 2);
        }
    }

    private final List<Hotspot> spots = new ArrayList<>();
    private final OrbitSpring glow = new OrbitSpring(0f, 90f, 16f);
    private final PanelSurface face = new PanelSurface();
    private final PanelSurface label = new PanelSurface();
    private Hotspot hovered;
    private Hotspot shown;

    SceneHotspots(PresetGarden garden, Vector3d feet) {
        for (PresetGarden.Spot s : PresetGarden.SPOTS) {
            Vector3d a = garden.toWorld(s.x0(), s.y0(), s.z0()), b = garden.toWorld(s.x1(), s.y1(), s.z1());
            spots.add(new Hotspot(s.label(), new Vector3d(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z)),
                    new Vector3d(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z)), s.target()));
        }
        spots.add(new Hotspot(AetherLang.localize("You"), new Vector3d(feet).add(-0.35, 0, -0.35),
                new Vector3d(feet).add(0.35, 1.85, 0.35), "wave"));
    }

    // the nearest hotspot the ray passes through, remembered as hovered
    Hotspot pick(Vector3d from, Vector3d dir) {
        Hotspot best = null;
        double bestT = Double.MAX_VALUE;
        for (Hotspot h : spots) {
            double t = enter(from, dir, h.min(), h.max());
            // the figure stands inside the fields' boxes, so it wins whenever the ray touches it
            if (t >= 0 && "wave".equals(h.target())) t *= 0.5;
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = h;
            }
        }
        hovered = best;
        return best;
    }

    void clear() {
        hovered = null;
    }

    Hotspot hovered() {
        return hovered;
    }

    // slab test: distance along the ray to the box, or -1 when it misses
    private static double enter(Vector3d o, Vector3d d, Vector3d min, Vector3d max) {
        double tMin = 0, tMax = Double.MAX_VALUE;
        double[] os = {o.x, o.y, o.z}, ds = {d.x, d.y, d.z}, mins = {min.x, min.y, min.z}, maxs = {max.x, max.y, max.z};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(ds[i]) < 1e-9) {
                if (os[i] < mins[i] || os[i] > maxs[i]) return -1;
                continue;
            }
            double t1 = (mins[i] - os[i]) / ds[i], t2 = (maxs[i] - os[i]) / ds[i];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return -1;
        }
        return tMin;
    }

    void step(float dt) {
        if (hovered != null) shown = hovered;
        glow.t = hovered != null ? 1f : 0f;
        glow.step(dt);
    }

    void appendQuads(List<OrbitWorldRenderer.Quad> out, OrbitLayout.Camera cam) {
        float a = OrbitRig.clamp(glow.x, 0f, 1f);
        if (shown == null || a < 0.01f) return;
        Palette p = Palette.fromTheme();
        int accent = p.accent();
        face.render(64f, 64f, 1f, nvg -> {
            nvg.rect(0f, 0f, 64f, 64f, Argb.withAlpha(accent, 0.16f));
            nvg.rectOutline(1f, 1f, 62f, 62f, 0f, 2f, Argb.withAlpha(0xFFFFFFFF, 0.85f));
        });
        Vector3d lo = new Vector3d(shown.min()).sub(0.02, 0.02, 0.02), hi = new Vector3d(shown.max()).add(0.02, 0.02, 0.02);
        double[] xs = {lo.x, hi.x}, ys = {lo.y, hi.y}, zs = {lo.z, hi.z};
        int t = face.texture();
        // six faces of the box, each a quad with the outlined texture
        out.add(new OrbitWorldRenderer.Quad(v(xs[0], ys[1], zs[0]), v(xs[1], ys[1], zs[0]), v(xs[1], ys[1], zs[1]), v(xs[0], ys[1], zs[1]), t, a, 0f));
        out.add(new OrbitWorldRenderer.Quad(v(xs[0], ys[1], zs[0]), v(xs[1], ys[1], zs[0]), v(xs[1], ys[0], zs[0]), v(xs[0], ys[0], zs[0]), t, a, 0f));
        out.add(new OrbitWorldRenderer.Quad(v(xs[0], ys[1], zs[1]), v(xs[1], ys[1], zs[1]), v(xs[1], ys[0], zs[1]), v(xs[0], ys[0], zs[1]), t, a, 0f));
        out.add(new OrbitWorldRenderer.Quad(v(xs[0], ys[1], zs[0]), v(xs[0], ys[1], zs[1]), v(xs[0], ys[0], zs[1]), v(xs[0], ys[0], zs[0]), t, a, 0f));
        out.add(new OrbitWorldRenderer.Quad(v(xs[1], ys[1], zs[0]), v(xs[1], ys[1], zs[1]), v(xs[1], ys[0], zs[1]), v(xs[1], ys[0], zs[0]), t, a, 0f));
        String text = AetherLang.localize(shown.label());
        label.render(400f, 44f, 2f, nvg -> SettingPreview.pill(nvg, 400f, 44f, text, accent));
        Vector3d at = shown.top();
        out.add(SettingPreview.billboard(at, cam, SettingPreview.labelWidth(at, cam) * 0.8, 44.0 / 400.0, label.texture(), a));
    }

    private static Vector3d v(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    @Override
    public void close() {
        face.close();
        label.close();
    }
}
