package dev.aether.ui.orbit;

import dev.aether.config.AetherConfig;
import dev.aether.config.RewarpPointPair;
import dev.aether.config.RewarpPointPairs;
import dev.aether.renderer.McIcons;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.plot.GardenFacts;
import dev.aether.ui.orbit.panel.PanelView;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import dev.aether.util.GardenPlots;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// what a hovered setting does, shown in the world around the player: the etherwarp radius as a ring, pests over
// their plots, rewarp points as beacons and the custom pitch and yaw as a view cone. rendering only
final class SettingPreview implements AutoCloseable {
    enum Kind { RADIUS, PESTS, REWARP, CONE }

    // what the preview reads each frame; the live game and the preview harness each fill one
    record World(Vector3d feet, double eyeHeight, float yaw, float pitch, GardenFacts garden, List<RewarpPointPair> rewarps) {
    }

    private static final Set<String> CONE = Set.of("Custom Pitch", "Pitch", "Custom Yaw", "Yaw", "Farming Pitch Range",
            "Farming Yaw Range");
    private static final Set<String> PESTS = Set.of("Pest Threshold", "Pest Destroyer Trigger Delay",
            "Leave One Pest Plots", "Pest Traps Plot");
    private static final int MARKERS = 12;
    private static final float LABEL_W = 400f;

    private final OrbitSpring[] shown = new OrbitSpring[Kind.values().length];
    private final PanelView.Hover[] last = new PanelView.Hover[Kind.values().length];
    private final PanelSurface ring = new PanelSurface();
    private final PanelSurface ringLabel = new PanelSurface();
    private final PanelSurface fence = new PanelSurface();
    private final PanelSurface cone = new PanelSurface();
    private final PanelSurface coneLabel = new PanelSurface();
    private final PanelSurface beamPest = new PanelSurface();
    private final PanelSurface beamStart = new PanelSurface();
    private final PanelSurface beamEnd = new PanelSurface();
    private final PanelSurface[] labels = new PanelSurface[MARKERS];

    SettingPreview() {
        for (int i = 0; i < shown.length; i++) shown[i] = new OrbitSpring(0f, 70f, 14f);
        for (int i = 0; i < labels.length; i++) labels[i] = new PanelSurface();
    }

    static Kind kindOf(PanelView.Hover hover) {
        if (hover == null) return null;
        Setting setting = hover.setting();
        String name = setting.getRawName();
        if (name.startsWith("Etherwarp Minimum Distance") && setting instanceof SliderSetting) return Kind.RADIUS;
        if (CONE.contains(name) && "farming-macro".equals(hover.pageId())) return Kind.CONE;
        if (name.contains("Rewarp") || hover.pageId() != null && hover.pageId().contains("rewarp")) return Kind.REWARP;
        if (PESTS.contains(name) || hover.group() != null && hover.group().startsWith("Pest Destroyer")) return Kind.PESTS;
        return null;
    }

    void step(float dt, PanelView.Hover hover, boolean allowed) {
        Kind kind = allowed ? kindOf(hover) : null;
        for (Kind k : Kind.values()) {
            OrbitSpring s = shown[k.ordinal()];
            s.t = k == kind ? 1f : 0f;
            if (k == kind) last[k.ordinal()] = hover;
            s.step(dt);
            if (s.x < 0.002f && s.t == 0f) last[k.ordinal()] = null;
        }
    }

    void appendQuads(List<OrbitWorldRenderer.Quad> out, World world, OrbitLayout.Camera cam, float time) {
        Palette p = Palette.fromTheme();
        float radius = alpha(Kind.RADIUS);
        if (radius > 0.01f && last[Kind.RADIUS.ordinal()].setting() instanceof SliderSetting slider) {
            radius(out, world, cam, slider, radius, p, time);
        }
        float pests = alpha(Kind.PESTS);
        if (pests > 0.01f) pests(out, world, cam, pests, p, time);
        float rewarp = alpha(Kind.REWARP);
        if (rewarp > 0.01f) rewarps(out, world, cam, rewarp, time);
        float cone = alpha(Kind.CONE);
        if (cone > 0.01f) cone(out, world, cam, cone, p, time);
    }

    boolean showing() {
        for (Kind k : Kind.values()) if (alpha(k) > 0.01f) return true;
        return false;
    }

    private float alpha(Kind kind) {
        return last[kind.ordinal()] == null ? 0f : OrbitRig.clamp(shown[kind.ordinal()].x, 0f, 1f);
    }

    // -- etherwarp radius ---------------------------------------------------------------------------------------

    private void radius(List<OrbitWorldRenderer.Quad> out, World world, OrbitLayout.Camera cam, SliderSetting slider,
                        float a, Palette p, float time) {
        double r = slider.getValue() * (0.9 + 0.1 * a);
        ring.render(512f, 512f, 1.5f, nvg -> {
            int c = p.accent();
            nvg.radialGradient(256f, 256f, 160f, 254f, Argb.withAlpha(c, 0f), Argb.withAlpha(c, 0.22f));
            nvg.circleOutline(256f, 256f, 250f, 5f, Argb.withAlpha(c, 0.95f));
            for (int i = 0; i < 72; i++) {
                if (i % 2 == 1) continue;
                double t0 = Math.PI * 2 * i / 72 + time * 0.05, t1 = Math.PI * 2 * (i + 1) / 72 + time * 0.05;
                nvg.line(256f + (float) Math.cos(t0) * 236f, 256f + (float) Math.sin(t0) * 236f,
                        256f + (float) Math.cos(t1) * 236f, 256f + (float) Math.sin(t1) * 236f, 2f, Argb.withAlpha(c, 0.5f));
            }
        });
        Vector3d f = world.feet();
        double y = f.y + 0.06;
        out.add(new OrbitWorldRenderer.Quad(new Vector3d(f.x - r, y, f.z - r), new Vector3d(f.x + r, y, f.z - r),
                new Vector3d(f.x + r, y, f.z + r), new Vector3d(f.x - r, y, f.z + r), ring.texture(), a, 0f));
        // a low glowing fence on the rim, since the ground ring alone is a thin line at the camera's angle
        fence.render(16f, 128f, 1f, nvg -> nvg.linearGradient(0f, 0f, 16f, 128f, 0f, Argb.withAlpha(p.accent(), 0f),
                Argb.withAlpha(p.accent(), 0.75f)));
        int segments = 64;
        double rise = 0.9 + 0.3 * Math.sin(time * 2.4);
        for (int i = 0; i < segments; i++) {
            double t0 = Math.PI * 2 * i / segments, t1 = Math.PI * 2 * (i + 1) / segments;
            double x0 = f.x + Math.cos(t0) * r, z0 = f.z + Math.sin(t0) * r;
            double x1 = f.x + Math.cos(t1) * r, z1 = f.z + Math.sin(t1) * r;
            out.add(new OrbitWorldRenderer.Quad(new Vector3d(x0, y + rise, z0), new Vector3d(x1, y + rise, z1),
                    new Vector3d(x1, y, z1), new Vector3d(x0, y, z0), fence.texture(), a, 0f));
        }
        String label = AetherLang.localize("Etherwarp beyond") + " " + Math.round(slider.getValue()) + " "
                + AetherLang.localize("blocks");
        ringLabel.render(LABEL_W, 44f, 2f, nvg -> pill(nvg, LABEL_W, 44f, label, p.accent()));
        Vector3d ahead = new Vector3d(cam.forward()).mul(1, 0, 1);
        if (ahead.lengthSquared() < 1e-6) ahead.set(0, 0, 1);
        ahead.normalize().mul(r);
        Vector3d at = new Vector3d(f).add(ahead).add(0, 2.6, 0);
        out.add(billboard(at, cam, labelWidth(at, cam), 44.0 / LABEL_W, ringLabel.texture(), a));
    }

    // -- pests over their plots ---------------------------------------------------------------------------------

    private void pests(List<OrbitWorldRenderer.Quad> out, World world, OrbitLayout.Camera cam, float a, Palette p, float time) {
        GardenFacts garden = world.garden();
        if (garden == null || !garden.onGarden() || garden.pests().isEmpty()) return;
        beam(beamPest, 0xFFFF5A5A);
        int index = 0;
        for (Map.Entry<Integer, Integer> entry : garden.pests().entrySet()) {
            if (index >= MARKERS) break;
            GardenPlots.Bounds bounds = GardenPlots.boundsForPlot(entry.getKey());
            if (bounds == null) continue;
            Vector3d base = new Vector3d(bounds.centerX(), world.feet().y, bounds.centerZ());
            double dist = base.distance(cam.pos());
            double scale = Math.max(1.6, dist * 0.045);
            double bob = Math.sin(time * 1.8 + index) * 0.15 * scale;
            Vector3d top = new Vector3d(base).add(0, 6 + scale * 1.6 + bob, 0);
            out.add(verticalBeam(base, top, cam, 0.25 * scale, beamPest.texture(), a * 0.8f));
            int count = entry.getValue();
            String plot = AetherLang.localize("Plot") + " " + entry.getKey();
            labels[index].render(120f, 150f, 2f, nvg -> pestTag(nvg, p, plot, count, time));
            out.add(billboard(top, cam, 1.6 * scale, 150.0 / 120.0, labels[index].texture(), a));
            index++;
        }
    }

    private static void pestTag(NVGRenderer nvg, Palette p, String plot, int count, float time) {
        float pulse = 0.8f + 0.2f * (float) Math.sin(time * 3);
        nvg.glowCircle(60f, 56f, 38f, Argb.withAlpha(0xFFFF5A5A, 0.5f * pulse), 1f);
        nvg.circle(60f, 56f, 36f, 0xE0140C0E);
        nvg.circleOutline(60f, 56f, 36f, 3f, 0xFFFF6B6B);
        nvg.mcIcon(McIcons.of("minecraft:silverfish_spawn_egg"), 36f, 32f, 48f, 0xFFFFFFFF);
        String n = Integer.toString(Math.max(1, count));
        nvg.circle(88f, 28f, 15f, 0xFFE53935);
        float tw = nvg.textWidth(Fonts.UI_BOLD, n, 17f);
        nvg.text(Fonts.UI_BOLD, n, 88f - tw / 2f, 19f, 17f, 0xFFFFFFFF);
        float pw = nvg.textWidth(Fonts.UI_SEMIBOLD, plot, 15f) + 20f;
        nvg.roundedRect(60f - pw / 2f, 108f, pw, 28f, 9f, 0xE00B0F14);
        nvg.text(Fonts.UI_SEMIBOLD, plot, 60f - pw / 2f + 10f, 113f, 15f, p.text());
    }

    // -- rewarp beacons -----------------------------------------------------------------------------------------

    private void rewarps(List<OrbitWorldRenderer.Quad> out, World world, OrbitLayout.Camera cam, float a, float time) {
        List<RewarpPointPair> pairs = world.rewarps();
        if (pairs == null) return;
        beam(beamStart, 0xFF5BE38A);
        beam(beamEnd, 0xFFFFA04A);
        int index = 0;
        for (int i = 0; i < pairs.size() && index + 1 < MARKERS; i++) {
            RewarpPointPair pair = pairs.get(i);
            String name = pair.name == null || pair.name.isBlank() ? AetherLang.localize("Rewarp") + " " + (i + 1) : pair.name;
            if (pair.startSet || Math.abs(pair.startX) + Math.abs(pair.startZ) > 0.01) {
                beacon(out, cam, new Vector3d(pair.startX, pair.startY, pair.startZ), beamStart, labels[index++],
                        AetherLang.localize("Start") + " · " + name, 0xFF5BE38A, a, time);
            }
            if (Math.abs(pair.endX) + Math.abs(pair.endZ) > 0.01) {
                beacon(out, cam, new Vector3d(pair.endX, pair.endY, pair.endZ), beamEnd, labels[index++],
                        AetherLang.localize("End") + " · " + name, 0xFFFFA04A, a, time);
            }
        }
    }

    private void beacon(List<OrbitWorldRenderer.Quad> out, OrbitLayout.Camera cam, Vector3d at, PanelSurface beam,
                        PanelSurface label, String text, int color, float a, float time) {
        double dist = at.distance(cam.pos());
        double scale = Math.max(1.0, dist * 0.06);
        Vector3d top = new Vector3d(at).add(0, 7 + scale * 2, 0);
        out.add(verticalBeam(at, top, cam, 0.35 * scale, beam.texture(), a));
        label.render(LABEL_W, 44f, 2f, nvg -> pill(nvg, LABEL_W, 44f, text, color));
        Vector3d at2 = new Vector3d(top).add(0, 0.5 * scale + Math.sin(time * 2) * 0.1, 0);
        out.add(billboard(at2, cam, labelWidth(at2, cam), 44.0 / LABEL_W, label.texture(), a));
    }

    // -- view cone ----------------------------------------------------------------------------------------------

    private void cone(List<OrbitWorldRenderer.Quad> out, World world, OrbitLayout.Camera cam, float a, Palette p, float time) {
        float yaw = AetherConfig.MACRO_USE_CUSTOM_YAW.get() ? AetherConfig.MACRO_CUSTOM_YAW.get().floatValue() : world.yaw();
        float pitch = AetherConfig.MACRO_USE_CUSTOM_PITCH.get() ? AetherConfig.MACRO_CUSTOM_PITCH.get().floatValue() : world.pitch();
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        Vector3d dir = new Vector3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr)).normalize();
        Vector3d side = new Vector3d(dir).cross(0, 1, 0);
        if (side.lengthSquared() < 1e-6) side.set(1, 0, 0);
        side.normalize();
        Vector3d lift = new Vector3d(side).cross(dir).normalize();
        Vector3d apex = new Vector3d(world.feet()).add(0, world.eyeHeight(), 0);
        double length = 7.0 * (0.85 + 0.15 * a);
        double spread = Math.tan(Math.toRadians(8));
        cone.render(64f, 256f, 1f, nvg -> {
            int c = p.accent();
            nvg.linearGradient(0f, 0f, 64f, 256f, 0f, Argb.withAlpha(c, 0.5f), Argb.withAlpha(c, 0.1f));
        });
        Vector3d center = new Vector3d(apex).fma(length, dir);
        int segments = 16;
        Vector3d[] rim = new Vector3d[segments];
        for (int i = 0; i < segments; i++) {
            double t = Math.PI * 2 * i / segments;
            rim[i] = new Vector3d(center).fma(Math.cos(t) * length * spread, side).fma(Math.sin(t) * length * spread, lift);
        }
        for (int i = 0; i < segments; i++) {
            Vector3d b0 = rim[i], b1 = rim[(i + 1) % segments];
            out.add(new OrbitWorldRenderer.Quad(new Vector3d(apex), new Vector3d(apex), new Vector3d(b1), new Vector3d(b0),
                    cone.texture(), a * 0.9f, 0f));
        }
        String label = String.format(Locale.ROOT, "%s %.1f° · %s %.1f°", AetherLang.localize("Yaw"), wrapYaw(yaw),
                AetherLang.localize("Pitch"), pitch);
        coneLabel.render(LABEL_W, 44f, 2f, nvg -> pill(nvg, LABEL_W, 44f, label, p.accent()));
        Vector3d at = new Vector3d(center).add(0, length * spread + 0.6, 0);
        out.add(billboard(at, cam, labelWidth(at, cam), 44.0 / LABEL_W, coneLabel.texture(), a));
    }

    private static float wrapYaw(float yaw) {
        float y = yaw % 360f;
        if (y >= 180f) y -= 360f;
        if (y < -180f) y += 360f;
        return y;
    }

    // -- shapes -------------------------------------------------------------------------------------------------

    private static void beam(PanelSurface surface, int color) {
        surface.render(32f, 256f, 1f, nvg -> {
            nvg.horizontalGradient(0f, 0f, 16f, 256f, 0f, Argb.withAlpha(color, 0f), Argb.withAlpha(color, 0.9f));
            nvg.horizontalGradient(16f, 0f, 16f, 256f, 0f, Argb.withAlpha(color, 0.9f), Argb.withAlpha(color, 0f));
            nvg.linearGradient(0f, 0f, 32f, 256f, 0f, 0x00FFFFFF, 0x66FFFFFF);
        });
    }

    static void pill(NVGRenderer nvg, float w, float h, String text, int accent) {
        float size = h * 0.42f;
        float tw = Math.min(w - 30f, nvg.textWidth(Fonts.UI_SEMIBOLD, text, size));
        float bw = tw + 30f;
        float x = (w - bw) / 2f;
        nvg.roundedRect(x, 2f, bw, h - 4f, (h - 4f) / 2f, 0xE60B0F14);
        nvg.rectOutline(x, 2f, bw, h - 4f, (h - 4f) / 2f, 1.5f, Argb.withAlpha(accent, 0.8f));
        nvg.circle(x + 12f, h / 2f, 3.5f, accent);
        nvg.text(Fonts.UI_SEMIBOLD, text, x + 21f, h / 2f - size * 0.62f, size, 0xFFF5F7FA);
    }

    // label pills keep about the same size on screen however far away they hang
    static double labelWidth(Vector3d at, OrbitLayout.Camera cam) {
        return Math.max(4.0, at.distance(cam.pos()) * 0.3);
    }

    // a quad facing the camera, centred on at, width w and height w * aspect
    static OrbitWorldRenderer.Quad billboard(Vector3d at, OrbitLayout.Camera cam, double w, double aspect, int texture, float a) {
        Vector3d right = new Vector3d(cam.right()).mul(w / 2);
        Vector3d up = new Vector3d(cam.up()).mul(w * aspect / 2);
        return new OrbitWorldRenderer.Quad(new Vector3d(at).sub(right).add(up), new Vector3d(at).add(right).add(up),
                new Vector3d(at).add(right).sub(up), new Vector3d(at).sub(right).sub(up), texture, a, 0f);
    }

    // an upright strip from base to top that turns about the vertical axis to face the camera
    private static OrbitWorldRenderer.Quad verticalBeam(Vector3d base, Vector3d top, OrbitLayout.Camera cam, double w,
                                                        int texture, float a) {
        Vector3d toward = new Vector3d(cam.pos()).sub(base).mul(1, 0, 1);
        if (toward.lengthSquared() < 1e-6) toward.set(0, 0, 1);
        Vector3d side = new Vector3d(toward).normalize().cross(0, 1, 0).mul(w / 2);
        return new OrbitWorldRenderer.Quad(new Vector3d(top).sub(side), new Vector3d(top).add(side),
                new Vector3d(base).add(side), new Vector3d(base).sub(side), texture, a, 0f);
    }

    @Override
    public void close() {
        ring.close();
        ringLabel.close();
        fence.close();
        cone.close();
        coneLabel.close();
        beamPest.close();
        beamStart.close();
        beamEnd.close();
        for (PanelSurface s : labels) s.close();
    }

    static List<RewarpPointPair> liveRewarps() {
        try {
            return new ArrayList<>(RewarpPointPairs.get());
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
