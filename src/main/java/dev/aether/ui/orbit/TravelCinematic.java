package dev.aether.ui.orbit;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.gui.Argb;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

// a short skippable film played over the menu when it opens on a different island than last time: the player
// runs across the old island, warps, and lands running on the new one. drawing only
final class TravelCinematic {
    interface FacePainter {
        void paint(NVGRenderer nvg, float x, float y, float size, float alpha);
    }

    static final float LENGTH = 2.6f;
    private static final float WARP = 1.3f;
    private static final float FADE_OUT = 0.4f;

    private final OrbitIsland from;
    private final OrbitIsland to;
    private final FacePainter face;
    private float t;

    TravelCinematic(OrbitIsland from, OrbitIsland to, FacePainter face) {
        this.from = from;
        this.to = to;
        this.face = face;
    }

    void step(float dt) {
        t += dt;
    }

    void skip() {
        t = Math.max(t, LENGTH - FADE_OUT);
    }

    boolean finished() {
        return t >= LENGTH;
    }

    // true once the film starts fading, so the menu can begin opening underneath it
    boolean revealing() {
        return t >= LENGTH - FADE_OUT;
    }

    void render(NVGRenderer nvg, float w, float h) {
        render(nvg, w, h, t);
    }

    void render(NVGRenderer nvg, float w, float h, float time) {
        float in = OrbitRig.smooth(OrbitRig.clamp(time / 0.3f, 0f, 1f));
        float out = OrbitRig.smooth(OrbitRig.clamp((LENGTH - time) / FADE_OUT, 0f, 1f));
        float a = Math.min(in, out);
        if (a <= 0.001f) return;
        nvg.save();
        nvg.globalAlpha(a);
        nvg.rect(0, 0, w, h, 0xF0050709);

        float swell = 1f + 0.08f * (1f - out);
        float sh = Math.round(h * 0.5f * swell);
        float sy = Math.round(h * 0.42f - sh / 2f);
        float scroll = time * w * 0.42f;
        OrbitIsland scene = time < WARP ? from : to;
        float gy = sy + sh * 0.8f;
        nvg.pushScissor(0, sy, w, sh);
        switch (scene) {
            case CRIMSON_ISLE -> crimson(nvg, w, sy, sh, gy, scroll, time);
            case GARDEN -> garden(nvg, w, sy, sh, gy, scroll, time);
            default -> plain(nvg, w, sy, sh, gy, scroll);
        }
        float rx = w * 0.36f;
        float rh = sh * 0.36f;
        runner(nvg, rx, gy + sh * 0.035f, rh, time);
        warp(nvg, w, sy, sh, rx, gy - rh * 0.5f, time);
        nvg.popScissor();
        nvg.rect(0, sy, w, 1f, 0x33FFFFFF);
        nvg.rect(0, sy + sh - 1f, w, 1f, 0x33FFFFFF);

        title(nvg, w, sy + sh + 16f, time);
        String skip = AetherLang.localize("Click to skip");
        float sw = nvg.textWidth(Fonts.UI_MEDIUM, skip, 7f);
        nvg.text(Fonts.UI_MEDIUM, skip, w - sw - 12f, sy - 13f, 7f, 0x8CFFFFFF);
        nvg.restore();
    }

    private void title(NVGRenderer nvg, float w, float y, float time) {
        String left = AetherLang.localize(from.label);
        String right = AetherLang.localize(to.label);
        float size = 15f;
        float lw = nvg.textWidth(Fonts.UI_BOLD, left, size);
        float rw = nvg.textWidth(Fonts.UI_BOLD, right, size);
        float gap = 34f;
        float x = (w - lw - gap - rw) / 2f;
        float arrive = OrbitRig.smooth(OrbitRig.clamp((time - WARP) / 0.35f, 0f, 1f));
        nvg.text(Fonts.UI_BOLD, left, x, y, size, Argb.withAlpha(0xFFFFFFFF, 1f - 0.45f * arrive));
        float ax = x + lw + 9f;
        float reach = OrbitRig.smooth(OrbitRig.clamp(time / WARP, 0f, 1f));
        float cy = y + size * 0.55f;
        nvg.line(ax, cy, ax + 16f * reach, cy, 1.5f, accent(to, 0.9f));
        if (reach > 0.6f) {
            float tip = ax + 16f * reach;
            nvg.line(tip - 4f, cy - 4f, tip, cy, 1.5f, accent(to, 0.9f));
            nvg.line(tip - 4f, cy + 4f, tip, cy, 1.5f, accent(to, 0.9f));
        }
        nvg.text(Fonts.UI_BOLD, right, x + lw + gap, y + (1f - arrive) * 5f, size, Argb.withAlpha(0xFFFFFFFF, 0.35f + 0.65f * arrive));
        String caption = AetherLang.localize(time < WARP ? "Warping" : "Arrived");
        float cw = nvg.textWidth(Fonts.UI_MEDIUM, caption, 7.5f);
        nvg.text(Fonts.UI_MEDIUM, caption, (w - cw) / 2f, y + size + 9f, 7.5f, 0x99FFFFFF);
    }

    private static int accent(OrbitIsland island, float alpha) {
        return Argb.withAlpha(island == OrbitIsland.CRIMSON_ISLE ? 0xFFFF7A2E : 0xFF8BD66B, alpha);
    }

    // -- the islands ------------------------------------------------------------------------------------------

    private static void garden(NVGRenderer nvg, float w, float sy, float sh, float gy, float scroll, float time) {
        nvg.linearGradient(0, sy, w, gy - sy, 0f, 0xFF5E9BE0, 0xFFCFE6F7);
        nvg.radialGradient(w * 0.78f, sy + sh * 0.22f, 4f, sh * 0.3f, 0x66FFF6C8, 0x00FFF6C8);
        nvg.circle(w * 0.78f, sy + sh * 0.22f, sh * 0.06f, 0xFFFFF4C2);
        tiles(w, scroll * 0.06f, w * 0.34f, (x, seed) -> {
            float cy = sy + sh * (0.16f + 0.1f * seed);
            nvg.roundedRect(x, cy, w * 0.11f, sh * 0.05f, 2f, 0xCCFFFFFF);
            nvg.roundedRect(x + w * 0.03f, cy - sh * 0.035f, w * 0.06f, sh * 0.05f, 2f, 0xCCFFFFFF);
        });
        hills(nvg, w, gy - sh * 0.05f, sh * 0.2f, scroll * 0.15f, 0xFF7FAE6E, 0.013f);
        hills(nvg, w, gy, sh * 0.13f, scroll * 0.3f, 0xFF5C8F4C, 0.021f);
        tiles(w, scroll * 0.45f, w * 0.62f, (x, seed) -> barn(nvg, x + w * 0.2f, gy, sh * 0.26f));
        tiles(w, scroll * 0.45f, w * 0.23f, (x, seed) -> tree(nvg, x, gy, sh * (0.18f + 0.08f * seed)));
        nvg.rect(0, gy, w, sy + sh - gy, 0xFF5B3A24);
        float row = 5f;
        float off = -(scroll % row);
        for (float x = off - row; x < w + row; x += row) {
            int k = Math.floorMod(Math.round((x + scroll) / row), 3);
            nvg.rect(x, gy, 1.5f, sy + sh - gy, 0x33000000);
            float stalk = sh * (0.07f + 0.02f * k);
            float sway = (float) Math.sin(time * 6 + x * 0.3f) * 0.8f;
            nvg.line(x + 3f, gy, x + 3f + sway, gy - stalk, 2f, 0xFF9DB04A);
            nvg.roundedRect(x + 1.6f + sway, gy - stalk - 3.5f, 3f, 5f, 1f, 0xFFE0C65A);
        }
        nvg.rect(0, gy, w, 2f, 0xFF3F7A33);
    }

    private static void crimson(NVGRenderer nvg, float w, float sy, float sh, float gy, float scroll, float time) {
        nvg.linearGradient(0, sy, w, gy - sy, 0f, 0xFF1C060B, 0xFF7A2A16);
        for (int i = 0; i < 26; i++) {
            float px = (float) ((hash(i * 3.1f) * w - scroll * 0.2f) % w + w) % w;
            float py = sy + sh * (1f - ((hash(i * 7.7f) + time * 0.12f) % 1f)) * 0.8f;
            nvg.circle(px, py, 0.9f + hash(i) * 0.8f, Argb.withAlpha(0xFFFFB070, 0.35f + 0.4f * hash(i * 1.3f)));
        }
        tiles(w, scroll * 0.12f, w * 0.09f, (x, seed) -> spire(nvg, x, gy - sh * 0.02f, sh * (0.28f + 0.3f * seed), w * 0.05f, 0xFF3A1A22));
        nvg.linearGradient(0, gy - sh * 0.18f, w, sh * 0.18f, 0f, 0x00FF5A1A, 0x55FF5A1A);
        tiles(w, scroll * 0.35f, w * 0.17f, (x, seed) -> basalt(nvg, x, gy, sh * (0.14f + 0.16f * seed), w * 0.07f, seed, time));
        nvg.rect(0, gy, w, sy + sh - gy, 0xFF201419);
        tiles(w, scroll, w * 0.29f, (x, seed) -> {
            float pw = w * (0.08f + 0.06f * seed);
            float pulse = 0.75f + 0.25f * (float) Math.sin(time * 4 + seed * 9f);
            nvg.radialGradient(x + pw / 2f, gy, 2f, pw * 0.8f, Argb.withAlpha(0xFFFF6A1A, 0.5f * pulse), 0x00FF6A1A);
            nvg.rect(x, gy, pw, 9f, 0xFFE8560F);
            nvg.linearGradient(x, gy, pw, 4f, 0f, 0xFFFFC04A, 0xFFFF7A1A);
            nvg.rect(x + pw * (0.2f + 0.1f * pulse), gy + 1f, pw * 0.3f, 1.2f, 0xFFFFE6A0);
        });
        float block = 9f;
        float off = -(scroll % block);
        for (float x = off - block; x < w + block; x += block) nvg.rect(x, gy + 9f, 1f, sy + sh - gy, 0x22000000);
        nvg.rect(0, gy, w, 2f, 0xFF4A2A2E);
    }

    private static void plain(NVGRenderer nvg, float w, float sy, float sh, float gy, float scroll) {
        nvg.linearGradient(0, sy, w, gy - sy, 0f, 0xFF4F78B8, 0xFFB8D2EE);
        hills(nvg, w, gy, sh * 0.16f, scroll * 0.25f, 0xFF6F9A64, 0.017f);
        nvg.rect(0, gy, w, sy + sh - gy, 0xFF6E6E6E);
    }

    // -- silhouettes ------------------------------------------------------------------------------------------

    private static void hills(NVGRenderer nvg, float w, float base, float amp, float scroll, int color, float freq) {
        nvg.beginPath();
        nvg.moveTo(0, base + 1f);
        for (float x = 0; x <= w + 8f; x += 8f) {
            float u = (x + scroll) * freq;
            nvg.lineTo(x, base - amp * (0.55f + 0.3f * (float) Math.sin(u) + 0.15f * (float) Math.sin(u * 2.3f + 1f)));
        }
        nvg.lineTo(w + 8f, base + 1f);
        nvg.closePath();
        nvg.fillPath(color);
    }

    private static void barn(NVGRenderer nvg, float x, float gy, float s) {
        float bw = s * 1.3f;
        float wall = s * 0.62f;
        nvg.rect(x, gy - wall, bw, wall, 0xFF9C3B2E);
        nvg.beginPath();
        nvg.moveTo(x - s * 0.08f, gy - wall);
        nvg.lineTo(x + bw / 2f, gy - s);
        nvg.lineTo(x + bw + s * 0.08f, gy - wall);
        nvg.closePath();
        nvg.fillPath(0xFF5A2A22);
        float dw = s * 0.36f, dh = s * 0.42f;
        float dx = x + (bw - dw) / 2f;
        nvg.rect(dx, gy - dh, dw, dh, 0xFF6E2A20);
        nvg.line(dx, gy - dh, dx + dw, gy, 1.2f, 0xFFE8DCCB);
        nvg.line(dx + dw, gy - dh, dx, gy, 1.2f, 0xFFE8DCCB);
        nvg.rectOutline(dx, gy - dh, dw, dh, 0f, 1.2f, 0xFFE8DCCB);
    }

    private static void tree(NVGRenderer nvg, float x, float gy, float s) {
        nvg.rect(x - s * 0.06f, gy - s * 0.5f, s * 0.12f, s * 0.5f, 0xFF6B4A2E);
        nvg.rect(x - s * 0.3f, gy - s, s * 0.6f, s * 0.55f, 0xFF3F7A3A);
        nvg.rect(x - s * 0.18f, gy - s * 1.15f, s * 0.36f, s * 0.2f, 0xFF4C8C44);
    }

    private static void spire(NVGRenderer nvg, float x, float base, float hgt, float wid, int color) {
        nvg.beginPath();
        nvg.moveTo(x - wid / 2f, base);
        nvg.lineTo(x - wid * 0.22f, base - hgt * 0.62f);
        nvg.lineTo(x - wid * 0.08f, base - hgt);
        nvg.lineTo(x + wid * 0.12f, base - hgt * 0.7f);
        nvg.lineTo(x + wid * 0.3f, base - hgt * 0.45f);
        nvg.lineTo(x + wid / 2f, base);
        nvg.closePath();
        nvg.fillPath(color);
    }

    private static void basalt(NVGRenderer nvg, float x, float gy, float hgt, float wid, float seed, float time) {
        float col = wid / 4f;
        for (int i = 0; i < 4; i++) {
            float ch = hgt * (0.6f + 0.4f * hash(seed * 31f + i * 13f));
            nvg.rect(x + i * col, gy - ch, col - 0.8f, ch, i % 2 == 0 ? 0xFF3B2C30 : 0xFF33262A);
            nvg.rect(x + i * col, gy - ch, col - 0.8f, 1.2f, 0xFF52404A);
        }
        if (hash(seed * 7f) > 0.55f) {
            float fx = x + col * 1.5f;
            float flicker = 0.8f + 0.2f * (float) Math.sin(time * 9 + seed * 20f);
            nvg.linearGradient(fx, gy - hgt * 0.85f, 2.5f, hgt * 0.85f, 1f, Argb.withAlpha(0xFFFFB347, flicker), 0xFFFF5A1A);
        }
    }

    // -- the runner -------------------------------------------------------------------------------------------

    // a blocky player in profile, minecraft proportions (8 px head, 12 px body and legs), limbs swinging
    private void runner(NVGRenderer nvg, float x, float feet, float height, float time) {
        float u = height / 32f;
        float phase = time * 15f;
        float swing = (float) Math.sin(phase) * 0.85f;
        float bob = Math.abs((float) Math.cos(phase)) * u * 1.2f;
        float hip = feet - 12f * u - bob;
        float shoulder = hip - 12f * u;
        nvg.radialGradient(x, feet + u, u, 9f * u, 0x55000000, 0x00000000);
        limb(nvg, x, shoulder, u, -swing, 0xFFA97C5E);
        limb(nvg, x, hip, u, swing, 0xFF2E2E80);
        nvg.rect(x - 2f * u, shoulder, 4f * u, 12f * u, 0xFF1C9AA0);
        nvg.rect(x - 2f * u, shoulder + 10f * u, 4f * u, 2f * u, 0xFF16797E);
        limb(nvg, x, hip, u, -swing, 0xFF3A3AA0);
        limb(nvg, x, shoulder, u, swing, 0xFFC69C7A);
        float hs = 8f * u;
        float hx = x - hs / 2f + u;
        float hy = shoulder - hs;
        nvg.rect(hx - 0.5f, hy - 0.5f, hs + 1f, hs + 1f, 0xFF2A1E16);
        face.paint(nvg, hx, hy, hs, 1f);
    }

    private static void limb(NVGRenderer nvg, float x, float pivotY, float u, float angle, int color) {
        nvg.save();
        nvg.translate(x, pivotY);
        nvg.rotate(angle);
        nvg.rect(-2f * u, 0, 4f * u, 12f * u, color);
        nvg.rect(-2f * u, 10.5f * u, 4f * u, 1.5f * u, Argb.mix(color, 0xFF000000, 0.25f));
        nvg.restore();
    }

    // the warp: speed lines gather, a column of light bursts at the runner and floods the strip white
    private void warp(NVGRenderer nvg, float w, float sy, float sh, float x, float y, float time) {
        float d = time - WARP;
        float charge = OrbitRig.clamp((time - (WARP - 0.45f)) / 0.45f, 0f, 1f);
        if (charge > 0f && d < 0f) {
            for (int i = 0; i < 14; i++) {
                float ly = sy + sh * (0.1f + 0.8f * hash(i * 5.3f));
                float len = w * (0.08f + 0.18f * hash(i * 2.1f)) * charge;
                float lx = (float) ((hash(i * 9.1f) * w + time * w * 2.2f) % (w + len)) - len;
                nvg.line(lx, ly, lx + len, ly, 1f, Argb.withAlpha(0xFFE6D8FF, 0.5f * charge));
            }
        }
        float burst = (float) Math.exp(-d * d / 0.018);
        if (burst > 0.005f) {
            int core = Argb.withAlpha(0xFFFFFFFF, burst);
            nvg.radialGradient(x, y, 2f, sh * (0.3f + 1.4f * burst), Argb.withAlpha(0xFFC9A8FF, 0.9f * burst), 0x00C9A8FF);
            float cw = 6f + 60f * burst;
            nvg.horizontalGradient(x - cw, sy, cw, sh, 0f, 0x00FFFFFF, core);
            nvg.horizontalGradient(x, sy, cw, sh, 0f, core, 0x00FFFFFF);
            nvg.rect(0, sy, w, sh, Argb.withAlpha(0xFFF4EEFF, Math.min(1f, burst * 1.15f)));
        }
    }

    // -- helpers ----------------------------------------------------------------------------------------------

    private interface Tile {
        void at(float x, float seed);
    }

    // tiles spaced about `gap` apart scrolling left; each keeps its seed while it crosses the strip
    private static void tiles(float w, float scroll, float gap, Tile tile) {
        long first = (long) Math.floor(scroll / gap) - 1;
        int n = (int) Math.ceil(w / gap) + 3;
        for (int i = 0; i < n; i++) {
            long index = first + i;
            float seed = hash(index * 0.618f + gap * 0.01f);
            tile.at(index * gap - scroll + (seed - 0.5f) * gap * 0.3f, seed);
        }
    }

    private static float hash(float v) {
        double s = Math.sin(v * 12.9898 + 78.233) * 43758.5453;
        return (float) (s - Math.floor(s));
    }
}
