package dev.aether.ui.gui.skin;

import dev.aether.ui.gui.GuiCanvas;

// draws a Glyph centred on (cx, cy) inside a size x size box; stroke is the line width for outlined marks
public final class Glyphs {
    private Glyphs() {
    }

    public static void draw(GuiCanvas c, Glyph glyph, float cx, float cy, float size, float stroke, int argb) {
        float s = size;
        switch (glyph) {
            case CHEVRON_DOWN -> polyline(c, stroke, argb, cx - s * 0.26f, cy - s * 0.12f, cx, cy + s * 0.14f,
                    cx + s * 0.26f, cy - s * 0.12f);
            case CHEVRON_UP -> polyline(c, stroke, argb, cx - s * 0.26f, cy + s * 0.12f, cx, cy - s * 0.14f,
                    cx + s * 0.26f, cy + s * 0.12f);
            case CHEVRON_LEFT -> polyline(c, stroke, argb, cx + s * 0.12f, cy - s * 0.26f, cx - s * 0.14f, cy,
                    cx + s * 0.12f, cy + s * 0.26f);
            case CHEVRON_RIGHT -> polyline(c, stroke, argb, cx - s * 0.12f, cy - s * 0.26f, cx + s * 0.14f, cy,
                    cx - s * 0.12f, cy + s * 0.26f);
            case CHECK -> polyline(c, stroke, argb, cx - s * 0.30f, cy + s * 0.01f, cx - s * 0.09f, cy + s * 0.22f,
                    cx + s * 0.31f, cy - s * 0.21f);
            case CROSS -> {
                polyline(c, stroke, argb, cx - s * 0.24f, cy - s * 0.24f, cx + s * 0.24f, cy + s * 0.24f);
                polyline(c, stroke, argb, cx + s * 0.24f, cy - s * 0.24f, cx - s * 0.24f, cy + s * 0.24f);
            }
            case PLUS -> {
                polyline(c, stroke, argb, cx - s * 0.28f, cy, cx + s * 0.28f, cy);
                polyline(c, stroke, argb, cx, cy - s * 0.28f, cx, cy + s * 0.28f);
            }
            case MINUS -> polyline(c, stroke, argb, cx - s * 0.28f, cy, cx + s * 0.28f, cy);
            case ARROW_UP -> arrow(c, stroke, argb, cx, cy, s, 0f, -1f);
            case ARROW_DOWN -> arrow(c, stroke, argb, cx, cy, s, 0f, 1f);
            case ARROW_LEFT -> arrow(c, stroke, argb, cx, cy, s, -1f, 0f);
            case ARROW_RIGHT -> arrow(c, stroke, argb, cx, cy, s, 1f, 0f);
            case LINK -> {
                polyline(c, stroke, argb, cx + s * 0.02f, cy - s * 0.30f, cx - s * 0.30f, cy - s * 0.30f,
                        cx - s * 0.30f, cy + s * 0.30f, cx + s * 0.30f, cy + s * 0.30f, cx + s * 0.30f, cy - s * 0.02f);
                polyline(c, stroke, argb, cx - s * 0.04f, cy + s * 0.04f, cx + s * 0.32f, cy - s * 0.32f);
                polyline(c, stroke, argb, cx + s * 0.10f, cy - s * 0.32f, cx + s * 0.32f, cy - s * 0.32f,
                        cx + s * 0.32f, cy - s * 0.10f);
            }
            case DOT -> c.circle(cx, cy, s * 0.2f, argb);
            case RING -> c.strokeCircle(cx, cy, s * 0.22f, stroke, argb);
            case ELLIPSIS -> {
                float r = Math.max(1f, s * 0.085f);
                c.circle(cx - s * 0.27f, cy, r, argb);
                c.circle(cx, cy, r, argb);
                c.circle(cx + s * 0.27f, cy, r, argb);
            }
            case SEARCH -> {
                c.strokeCircle(cx - s * 0.06f, cy - s * 0.06f, s * 0.23f, stroke, argb);
                polyline(c, stroke, argb, cx + s * 0.12f, cy + s * 0.12f, cx + s * 0.31f, cy + s * 0.31f);
            }
            case TRIANGLE_RIGHT -> filled(c, argb, cx - s * 0.12f, cy - s * 0.22f, cx + s * 0.18f, cy,
                    cx - s * 0.12f, cy + s * 0.22f);
            case TRIANGLE_DOWN -> filled(c, argb, cx - s * 0.22f, cy - s * 0.12f, cx + s * 0.22f, cy - s * 0.12f,
                    cx, cy + s * 0.18f);
            case DIAMOND -> filled(c, argb, cx, cy - s * 0.26f, cx + s * 0.26f, cy, cx, cy + s * 0.26f,
                    cx - s * 0.26f, cy);
            case PLAY -> filled(c, argb, cx - s * 0.20f, cy - s * 0.27f, cx + s * 0.27f, cy, cx - s * 0.20f,
                    cy + s * 0.27f);
            case RESET -> {
                arc(c, stroke, argb, cx, cy, s * 0.27f, -150f, 160f);
                double end = Math.toRadians(-150f);
                float ex = cx + (float) Math.cos(end) * s * 0.27f;
                float ey = cy + (float) Math.sin(end) * s * 0.27f;
                polyline(c, stroke, argb, ex - s * 0.02f, ey - s * 0.19f, ex, ey, ex + s * 0.19f, ey + s * 0.02f);
            }
            case WARNING -> {
                polyline(c, stroke, argb, cx, cy - s * 0.32f, cx + s * 0.34f, cy + s * 0.28f,
                        cx - s * 0.34f, cy + s * 0.28f, cx, cy - s * 0.32f);
                polyline(c, stroke, argb, cx, cy - s * 0.08f, cx, cy + s * 0.06f);
                c.circle(cx, cy + s * 0.17f, Math.max(0.8f, stroke * 0.6f), argb);
            }
            case INFO -> {
                c.strokeCircle(cx, cy, s * 0.32f, stroke, argb);
                polyline(c, stroke, argb, cx, cy - s * 0.02f, cx, cy + s * 0.16f);
                c.circle(cx, cy - s * 0.14f, Math.max(0.8f, stroke * 0.6f), argb);
            }
            case GRIP -> {
                float r = Math.max(0.8f, s * 0.07f);
                for (int row = -1; row <= 1; row++) {
                    c.circle(cx - s * 0.12f, cy + row * s * 0.22f, r, argb);
                    c.circle(cx + s * 0.12f, cy + row * s * 0.22f, r, argb);
                }
            }
        }
    }

    private static void arrow(GuiCanvas c, float stroke, int argb, float cx, float cy, float s, float dx, float dy) {
        float len = s * 0.30f;
        float head = s * 0.20f;
        float tipX = cx + dx * len;
        float tipY = cy + dy * len;
        polyline(c, stroke, argb, cx - dx * len, cy - dy * len, tipX, tipY);
        // the head's two barbs sit perpendicular to the shaft, pulled back from the tip
        float px = -dy;
        float py = dx;
        polyline(c, stroke, argb, tipX - dx * head + px * head, tipY - dy * head + py * head, tipX, tipY,
                tipX - dx * head - px * head, tipY - dy * head - py * head);
    }

    private static void arc(GuiCanvas c, float stroke, int argb, float cx, float cy, float r, float fromDeg,
                            float sweepDeg) {
        int steps = 18;
        c.beginPath();
        for (int i = 0; i <= steps; i++) {
            double angle = Math.toRadians(fromDeg + sweepDeg * i / steps);
            float x = cx + (float) Math.cos(angle) * r;
            float y = cy + (float) Math.sin(angle) * r;
            if (i == 0) {
                c.moveTo(x, y);
            } else {
                c.lineTo(x, y);
            }
        }
        c.strokePath(stroke, argb);
    }

    private static void polyline(GuiCanvas c, float stroke, int argb, float... points) {
        c.beginPath();
        c.moveTo(points[0], points[1]);
        for (int i = 2; i + 1 < points.length; i += 2) {
            c.lineTo(points[i], points[i + 1]);
        }
        c.strokePath(stroke, argb);
    }

    private static void filled(GuiCanvas c, int argb, float... points) {
        c.beginPath();
        c.moveTo(points[0], points[1]);
        for (int i = 2; i + 1 < points.length; i += 2) {
            c.lineTo(points[i], points[i + 1]);
        }
        c.closePath();
        c.fillPath(argb);
    }
}
