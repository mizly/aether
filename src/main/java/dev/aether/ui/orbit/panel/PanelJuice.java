package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.Rect;

import java.util.ArrayList;
import java.util.List;

// the front panel's feedback layer, drawn over its content: a ripple wherever you press, sparks when something
// switches on, and a soft sheen that follows the cursor across the glass
final class PanelJuice {
    private static final float RIPPLE_S = 0.45f;

    private record Ripple(float x, float y, long born) {
    }

    private static final class Spark {
        float x, y, vx, vy, age, life, size;
        int color;
    }

    private final List<Ripple> ripples = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private float pressX, pressY;
    private long last = -1L;

    void press(float x, float y, long now) {
        pressX = x;
        pressY = y;
        ripples.add(new Ripple(x, y, now));
        if (ripples.size() > 8) ripples.remove(0);
    }

    // a ring of sparks out from the last press, for a switch turning on
    void burst(int color) {
        int n = 14;
        for (int i = 0; i < n; i++) {
            Spark s = new Spark();
            double a = (i + Math.random() * 0.6) / n * Math.PI * 2;
            float speed = 70f + (float) Math.random() * 90f;
            s.x = pressX;
            s.y = pressY;
            s.vx = (float) Math.cos(a) * speed;
            s.vy = (float) Math.sin(a) * speed - 30f;
            s.life = 0.45f + (float) Math.random() * 0.3f;
            s.size = 1.6f + (float) Math.random() * 1.8f;
            s.color = i % 3 == 0 ? 0xFFFFFFFF : color;
            sparks.add(s);
        }
    }

    void draw(GuiCanvas c, Palette p, Rect area, float mx, float my, long now) {
        float dt = last < 0L ? 0f : Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        c.save();
        c.clip(area);
        if (mx >= 0f && my >= 0f && area.contains(mx, my)) {
            int glow = p.light() ? Argb.withAlpha(p.accent(), 0.06f) : 0x0EFFFFFF;
            c.legacy(nvg -> nvg.radialGradient(mx, my, 0f, 190f, glow, glow & 0x00FFFFFF));
        }
        for (int i = ripples.size() - 1; i >= 0; i--) {
            Ripple r = ripples.get(i);
            float k = (now - r.born()) / 1e9f / RIPPLE_S;
            if (k >= 1f || k < 0f) {
                ripples.remove(i);
                continue;
            }
            float e = 1f - (1f - k) * (1f - k) * (1f - k);
            float radius = 6f + 70f * e;
            c.circle(r.x(), r.y(), radius, Argb.withAlpha(p.accent(), 0.10f * (1f - k)));
            c.strokeCircle(r.x(), r.y(), radius, 1.5f, Argb.withAlpha(p.accent(), 0.4f * (1f - k)));
        }
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.age += dt;
            if (s.age >= s.life) {
                sparks.remove(i);
                continue;
            }
            float drag = (float) Math.pow(0.02, dt);
            s.vx *= drag;
            s.vy = s.vy * drag + 160f * dt;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            float k = s.age / s.life;
            c.circle(s.x, s.y, s.size * (1f - k * 0.6f), Argb.withAlpha(s.color, 1f - k));
        }
        c.restore();
    }
}
