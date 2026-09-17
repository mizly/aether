package dev.aether.renderer;

import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.nanovg.NanoVG.*;

// only safe to call between NanoVGManager.beginFrame and endFrame; get it from NanoVGManager.getRenderer()
// coords are logical pixels, colors are 0xAARRGGBB ints, and the NVGColor/NVGPaint scratch objects are reused each frame
public class NVGRenderer {

    private final long vg;

    // Pre-allocated NanoVG structs - never GC'd, reused every draw call
    private final NVGColor c1   = NVGColor.malloc();
    private final NVGColor c2   = NVGColor.malloc();
    private final NVGPaint paint = NVGPaint.malloc();
    private final MinecraftTextRenderer minecraftText;

    // Font measurement scratch buffer
    private final float[] fontBounds = new float[4];

    // Scissor stack for nested clipping
    private ScissorRegion scissorStack = null;

    // Per-font cached text width at the effective render size.
    private static final int MAX_TEXT_WIDTH_CACHE = 512;
    private final Map<String, Float> textWidthCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Float> eldest) {
            return size() > MAX_TEXT_WIDTH_CACHE;
        }
    };
    private String lastTextKey = null;

    private float textScale = 1f;

    NVGRenderer(long vg) {
        this.vg = vg;
        this.minecraftText = new MinecraftTextRenderer(vg, paint);
    }

    void beginMinecraftTextFrame() { minecraftText.beginFrame(); }
    void endMinecraftTextFrame() { minecraftText.endFrame(); }

    public void minecraftText(net.minecraft.client.gui.Font.PreparedText text) {
        text.visit(minecraftText);
    }

    // -- Basic shapes ----------------------------------------------------------

    public void rect(float x, float y, float w, float h, int color) {
        nvgBeginPath(vg);
        nvgRect(vg, x, y, w, h);
        color(color, c1);
        nvgFillColor(vg, c1);
        nvgFill(vg);
    }

    public void roundedRect(float x, float y, float w, float h, float radius, int color) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, w, h, radius);
        color(color, c1);
        nvgFillColor(vg, c1);
        nvgFill(vg);
    }


    public void rectOutline(float x, float y, float w, float h, float radius, float thickness, int color) {
        float half = thickness / 2f;
        float r = Math.min(radius, Math.min(w, h) / 2f);
        color(color, c1);

        if (r < 0.5f) {
            // Sharp corners: four non-overlapping filled rects
            rect(x - half, y - half, w + thickness, thickness, color);
            rect(x - half, y + h - half, w + thickness, thickness, color);
            rect(x - half, y + half, thickness, h - thickness, color);
            rect(x + w - half, y + half, thickness, h - thickness, color);
            return;
        }

        // Single open stroke path tracing the full outline - tangent-continuous at every
        // arc-to-line transition (no joins), so alpha is uniform at any opacity.
        // NVG_STENCIL_STROKES ensures the open endpoint at the top-edge midpoint is fine.
        nvgStrokeWidth(vg, thickness);
        nvgLineCap(vg, NVG_BUTT);
        nvgStrokeColor(vg, c1);
        nvgBeginPath(vg);
        nvgMoveTo(vg, x + w / 2f, y);
        nvgLineTo(vg, x + w - r,  y);
        nvgArc(vg, x + w - r, y + r,     r, HALF_PI * 3, HALF_PI * 4, NVG_CW); // top-right
        nvgLineTo(vg, x + w,      y + h - r);
        nvgArc(vg, x + w - r, y + h - r, r, 0,           HALF_PI,     NVG_CW); // bottom-right
        nvgLineTo(vg, x + r,      y + h);
        nvgArc(vg, x + r,     y + h - r, r, HALF_PI,     HALF_PI * 2, NVG_CW); // bottom-left
        nvgLineTo(vg, x,          y + r);
        nvgArc(vg, x + r,     y + r,     r, HALF_PI * 2, HALF_PI * 3, NVG_CW); // top-left
        nvgLineTo(vg, x + w / 2f, y);
        nvgStroke(vg);
    }

    // bakes alpha into rgb premultiplied against black so the stroke is fully opaque, which dodges nanovg's semi-transparent stroke artefacts but assumes a dark background
    public void rectOutlineSolid(float x, float y, float w, float h, float radius, float thickness, int color) {
        rectOutline(x, y, w, h, radius, thickness, opaqueFromAlpha(color));
    }

    // scissor expands vertically from the midpoint: 0 draws nothing, 0.5 clips the horizontal edges, 1 is the full outline
    public void rectOutlineVerticalSides(float x, float y, float w, float h, float radius, float thickness, int color, float ratio) {
        if (ratio <= 0f) return;
        if (ratio >= 1f) {
            rectOutlineSolid(x, y, w, h, radius, thickness, color);
            return;
        }
        float r    = Math.min(radius, Math.min(w, h) / 2f);
        float half = thickness / 2f;
        int   c    = opaqueFromAlpha(color);

        if (ratio <= 0.4f) {
            // Phase 1: scissor shrinks from h/2 down to half, revealing verticals + corners.
            // Scissor stops at y+half so the horizontal stroke (centred on y) stays clipped.
            float pad = h / 2f - (ratio / 0.4f) * (h / 2f - half);
            nvgSave(vg);
            nvgIntersectScissor(vg, x - half, y + pad, w + thickness, h - pad * 2f);
            rectOutlineSolid(x, y, w, h, radius, thickness, color);
            nvgRestore(vg);
        } else {
            // Phase 2: no scissor. Draw corners + verticals as a stroke path (no horizontal
            // segments), then grow horizontal rects from each corner toward the centre.
            // Using an explicit arc path avoids any scissor clipping at the corner endpoints.
            // Colors are fully opaque here so arc stroke artefacts are not an issue.
            color(c, c1);
            nvgStrokeWidth(vg, thickness);
            nvgLineCap(vg, NVG_BUTT);
            nvgStrokeColor(vg, c1);
            nvgBeginPath(vg);
            // Left side: top-left arc → left straight → bottom-left arc
            nvgMoveTo(vg, x + r,         y);
            nvgArc(vg,   x + r,         y + r,         r, HALF_PI * 3, HALF_PI * 2, NVG_CCW);
            nvgLineTo(vg, x,             y + h - r);
            nvgArc(vg,   x + r,         y + h - r,     r, HALF_PI * 2, HALF_PI,     NVG_CCW);
            // Right side: top-right arc → right straight → bottom-right arc
            nvgMoveTo(vg, x + w - r,     y);
            nvgArc(vg,   x + w - r,     y + r,         r, HALF_PI * 3, HALF_PI * 4, NVG_CW);
            nvgLineTo(vg, x + w,         y + h - r);
            nvgArc(vg,   x + w - r,     y + h - r,     r, 0,           HALF_PI,     NVG_CW);
            nvgStroke(vg);

            float segLen = ((ratio - 0.4f) / 0.6f) * (w / 2f - r);
            rect(x + r,              y - half, segLen, thickness, c);  // top-left  →
            rect(x + w - r - segLen, y - half, segLen, thickness, c);  // top-right ←
            rect(x + r,              y + h - half, segLen, thickness, c);  // bot-left  →
            rect(x + w - r - segLen, y + h - half, segLen, thickness, c);  // bot-right ←
        }
    }

    // same, stroked with a top-left to bottom-right gradient
    public void rectOutlineVerticalSidesGradient(float x, float y, float w, float h, float radius,
                                                   float thickness, int colorTL, int colorBR, float ratio) {
        if (ratio <= 0f) return;
        int c1c = opaqueFromAlpha(colorTL);
        int c2c = opaqueFromAlpha(colorBR);
        color(c1c, c1);
        color(c2c, c2);
        nvgLinearGradient(vg, x, y, x + w, y + h, c1, c2, paint);
        float r    = Math.min(radius, Math.min(w, h) / 2f);
        float half = thickness / 2f;
        nvgStrokeWidth(vg, thickness);
        nvgLineCap(vg, NVG_BUTT);
        nvgStrokePaint(vg, paint);

        if (ratio >= 1f) {
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + w / 2f, y);
            nvgLineTo(vg, x + w - r,  y);
            nvgArc(vg, x + w - r, y + r,     r, HALF_PI * 3, HALF_PI * 4, NVG_CW);
            nvgLineTo(vg, x + w,      y + h - r);
            nvgArc(vg, x + w - r, y + h - r, r, 0,           HALF_PI,     NVG_CW);
            nvgLineTo(vg, x + r,      y + h);
            nvgArc(vg, x + r,     y + h - r, r, HALF_PI,     HALF_PI * 2, NVG_CW);
            nvgLineTo(vg, x,          y + r);
            nvgArc(vg, x + r,     y + r,     r, HALF_PI * 2, HALF_PI * 3, NVG_CW);
            nvgLineTo(vg, x + w / 2f, y);
            nvgStroke(vg);
        } else if (ratio <= 0.4f) {
            float pad = h / 2f - (ratio / 0.4f) * (h / 2f - half);
            nvgSave(vg);
            nvgIntersectScissor(vg, x - half, y + pad, w + thickness, h - pad * 2f);
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + w / 2f, y);
            nvgLineTo(vg, x + w - r,  y);
            nvgArc(vg, x + w - r, y + r,     r, HALF_PI * 3, HALF_PI * 4, NVG_CW);
            nvgLineTo(vg, x + w,      y + h - r);
            nvgArc(vg, x + w - r, y + h - r, r, 0,           HALF_PI,     NVG_CW);
            nvgLineTo(vg, x + r,      y + h);
            nvgArc(vg, x + r,     y + h - r, r, HALF_PI,     HALF_PI * 2, NVG_CW);
            nvgLineTo(vg, x,          y + r);
            nvgArc(vg, x + r,     y + r,     r, HALF_PI * 2, HALF_PI * 3, NVG_CW);
            nvgLineTo(vg, x + w / 2f, y);
            nvgStroke(vg);
            nvgRestore(vg);
        } else {
            // Phase 2: corners + verticals stroke, then gradient-filled horizontal rects
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + r,     y);
            nvgArc(vg,   x + r,     y + r,     r, HALF_PI * 3, HALF_PI * 2, NVG_CCW);
            nvgLineTo(vg, x,         y + h - r);
            nvgArc(vg,   x + r,     y + h - r, r, HALF_PI * 2, HALF_PI,     NVG_CCW);
            nvgMoveTo(vg, x + w - r, y);
            nvgArc(vg,   x + w - r, y + r,     r, HALF_PI * 3, HALF_PI * 4, NVG_CW);
            nvgLineTo(vg, x + w,     y + h - r);
            nvgArc(vg,   x + w - r, y + h - r, r, 0,           HALF_PI,     NVG_CW);
            nvgStroke(vg);
            float segLen = ((ratio - 0.4f) / 0.6f) * (w / 2f - r);
            nvgFillPaint(vg, paint);
            nvgBeginPath(vg);
            nvgRect(vg, x + r,              y - half,     segLen, thickness);
            nvgRect(vg, x + w - r - segLen, y - half,     segLen, thickness);
            nvgRect(vg, x + r,              y + h - half, segLen, thickness);
            nvgRect(vg, x + w - r - segLen, y + h - half, segLen, thickness);
            nvgFill(vg);
        }
    }

    private static final float HALF_PI = (float) (Math.PI / 2);

    // premultiplies alpha against black, e.g. 0x80FF0000 -> 0xFF7F0000
    private static int opaqueFromAlpha(int argb) {
        int a = (argb >> 24) & 0xFF;
        if (a == 0xFF) return argb;
        int r = ((argb >> 16) & 0xFF) * a / 255;
        int g = ((argb >>  8) & 0xFF) * a / 255;
        int b = ( argb        & 0xFF) * a / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public void circle(float cx, float cy, float radius, int color) {
        nvgBeginPath(vg);
        nvgCircle(vg, cx, cy, radius);
        color(color, c1);
        nvgFillColor(vg, c1);
        nvgFill(vg);
    }

    public void line(float x1, float y1, float x2, float y2, float thickness, int color) {
        nvgBeginPath(vg);
        nvgMoveTo(vg, x1, y1);
        nvgLineTo(vg, x2, y2);
        nvgStrokeWidth(vg, thickness);
        color(color, c1);
        nvgStrokeColor(vg, c1);
        nvgStroke(vg);
    }

    public void polyline(float[] points, int count, float thickness, int color) {
        if (count < 2) return;
        nvgBeginPath(vg);
        nvgMoveTo(vg, points[0], points[1]);
        for (int i = 1; i < count; i++) nvgLineTo(vg, points[i * 2], points[i * 2 + 1]);
        nvgStrokeWidth(vg, thickness);
        color(color, c1);
        nvgStrokeColor(vg, c1);
        nvgStroke(vg);
    }

    // -- Gradients -------------------------------------------------------------

    public void linearGradient(float x, float y, float w, float h, float radius,
                                int colorTop, int colorBottom) {
        color(colorTop,    c1);
        color(colorBottom, c2);
        nvgLinearGradient(vg, x, y, x, y + h, c1, c2, paint);
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, w, h, radius);
        nvgFillPaint(vg, paint);
        nvgFill(vg);
    }

    public void horizontalGradient(float x, float y, float w, float h, float radius,
                                    int colorLeft, int colorRight) {
        color(colorLeft,  c1);
        color(colorRight, c2);
        nvgLinearGradient(vg, x, y, x + w, y, c1, c2, paint);
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, w, h, radius);
        nvgFillPaint(vg, paint);
        nvgFill(vg);
    }

    // -- Shadow / Glow ---------------------------------------------------------

    // draw this before the element it belongs to so it lands underneath
    public void shadow(float x, float y, float w, float h, float radius, float blur, int color) {
        color(color, c1);
        color(color & 0x00FFFFFF, c2); // same RGB, zero alpha
        nvgBoxGradient(vg, x, y + blur * 0.5f, w, h, radius + blur * 0.5f, blur, c1, c2, paint);
        nvgBeginPath(vg);
        nvgRect(vg, x - blur, y - blur * 0.5f, w + blur * 2, h + blur * 2);
        nvgFillPaint(vg, paint);
        nvgFill(vg);
    }

    // layered box gradients; intensity in [0.2, 1.0] goes from subtle to strong
    public void glow(float x, float y, float w, float h, float radius, int color, float intensity) {
        intensity = Math.max(0.1f, Math.min(1f, intensity));
        int layers = (int) (intensity * 4) + 1;        // 1-5 layers
        int baseAlpha = (color >> 24) & 0xFF;

        for (int i = 0; i < layers; i++) {
            float spread  = (i + 1) * 6f * intensity;
            float feather = spread * 1.5f;
            int layerAlpha = (int) (baseAlpha * (1f - (float) i / layers) * intensity);
            if (layerAlpha < 2) continue;

            color((layerAlpha << 24) | (color & 0x00FFFFFF), c1);
            color(color & 0x00FFFFFF, c2);
            nvgBoxGradient(vg, x, y, w, h, radius, feather, c1, c2, paint);
            nvgBeginPath(vg);
            nvgRect(vg, x - spread, y - spread, w + spread * 2, h + spread * 2);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
        }
    }

    public void glowCircle(float cx, float cy, float radius, int color, float intensity) {
        intensity = Math.max(0.1f, Math.min(1f, intensity));
        int layers = (int) (intensity * 3) + 1;
        int baseAlpha = (color >> 24) & 0xFF;

        for (int i = 0; i < layers; i++) {
            float spread  = (i + 1) * 8f * intensity;
            int layerAlpha = (int) (baseAlpha * (1f - (float) i / layers) * intensity);
            if (layerAlpha < 2) continue;

            color((layerAlpha << 24) | (color & 0x00FFFFFF), c1);
            color(color & 0x00FFFFFF, c2);
            nvgRadialGradient(vg, cx, cy, radius * 0.5f, radius + spread, c1, c2, paint);
            nvgBeginPath(vg);
            nvgCircle(vg, cx, cy, radius + spread);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
        }

        // Draw filled centre circle
        circle(cx, cy, radius, color);
    }

    // an approximation, not a real gaussian - it composites a semi-transparent overlay that suggests frosting
    // for a true two-pass blur see BlurFramebuffer

    public void blur(float x, float y, float w, float h, float blurRadius) {
        float clampedBlur = Math.min(blurRadius, 30f);
        // Dark semi-transparent base
        rect(x, y, w, h, 0x40000000);
        // Radial vignette for depth
        shadow(x, y, w, h, 0f, clampedBlur, 0x28000000);
        // Frost tint
        rect(x, y, w, h, 0x0CFFFFFF);
    }

    // everything is clipped to the rounded shape, so no scissor is needed and nothing bleeds past the corners
    public void blur(float x, float y, float w, float h, float radius, float blurRadius) {
        float clampedBlur = Math.min(blurRadius, 30f);
        // Dark semi-transparent base
        roundedRect(x, y, w, h, radius, 0x40000000);
        // Vignette for depth, filled inside the rounded shape only
        color(0x28000000, c1);
        color(0x00000000, c2);
        nvgBoxGradient(vg, x, y + clampedBlur * 0.5f, w, h, radius + clampedBlur * 0.5f, clampedBlur, c1, c2, paint);
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, w, h, radius);
        nvgFillPaint(vg, paint);
        nvgFill(vg);
        // Frost tint
        roundedRect(x, y, w, h, radius, 0x0CFFFFFF);
    }


    // same approximation as blur(), circular
    public void blurCircle(float cx, float cy, float radius, float blurRadius) {
        // Limit "weight" to keep it tasteful & avoid overdraw spikes
        float b = Math.min(Math.max(blurRadius, 0f), 30f);

        // 1) Dark semi-transparent base (matches 0x40000000 used in rect blur)
        circle(cx, cy, radius, 0x40000000);

        // 2) Radial vignette: layer a few outward circles with fading alpha.
        //    This approximates the soft edge from shadow(...).
        final int STEPS = 5;                  // 4-6 looks good, low cost
        final int MAX_A = 0x28;               // 0x28000000 used in rect blur's shadow
        for (int i = 1; i <= STEPS; i++) {
            float t  = i / (float) STEPS;     // 0..1
            float rr = radius + t * b;        // grow radius outward
            int a    = Math.round(MAX_A * (1f - t)); // fade alpha
            // black with 'a' alpha -> ARGB
            int argb = ((a & 0xFF) << 24);
            circle(cx, cy, rr, argb);
        }

        // 3) Frost tint (very light) - matches 0x0CFFFFFF from the rect blur
        circle(cx, cy, radius, 0x0CFFFFFF);
    }


    public void blurRingApprox(float cx, float cy, float radius, float blurRadius, float alpha) {
        float b = Math.min(Math.max(blurRadius, 0f), 30f);
        final int STEPS = 5;
        int maxA = Math.round(Math.max(0f, Math.min(alpha, 1f)) * 0x28); // scale peak alpha

        // No base fill - only outward halo
        for (int i = 1; i <= STEPS; i++) {
            float t  = i / (float) STEPS;
            float rr = radius + t * b;
            int a    = Math.round(maxA * (1f - t));
            int argb = ((a & 0xFF) << 24);
            circle(cx, cy, rr, argb);
        }

        // Optional: an ultra-thin faint rim to help readability at very low alpha
        // circle(cx, cy, radius, 0x10FFFFFF);
    }

    public void circleOutline(float cx, float cy, float radius, float strokeWidth, int color) {
        nvgBeginPath(vg);
        nvgCircle   (vg, cx, cy, Math.max(0.5f, radius));
        nvgStrokeWidth (vg, Math.max(0.5f, strokeWidth));
        color(color, c1);
        nvgStrokeColor (vg, c1);
        nvgStroke(vg);
    }


    // -- SVG rendering ---------------------------------------------------------

    // svgs are parsed, rasterized and cached as nvg images on first use; color 0 means no tint
    public void renderSVG(String resourcePath, float x, float y, float width, float height, int color) {
        SVGRenderer.render(vg, resourcePath, x, y, width, height, color, paint);
    }

    public int createImageRGBA(int width, int height, java.nio.ByteBuffer pixels) {
        return nvgCreateImageRGBA(vg, width, height, 0, pixels);
    }

    public void deleteImage(int handle) {
        if (handle != -1) nvgDeleteImage(vg, handle);
    }

    public void image(int handle, float x, float y, float width, float height, float radius, float alpha) {
        if (handle == -1) return;
        nvgSave(vg);
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, radius);
        nvgImagePattern(vg, x, y, width, height, 0f, handle, alpha, paint);
        nvgFillPaint(vg, paint);
        nvgFill(vg);
        nvgRestore(vg);
    }

    // -- Text ------------------------------------------------------------------

    public void text(String fontName, String text, float x, float y, float size, int color) {
        textLiteral(fontName, AetherLang.localize(text), x, y, size * textScale, color);
    }

    // Server text must not be localized or inherit the settings menu's text scale.
    public void textLiteral(String fontName, String text, float x, float y, float size, int color) {
        int fontId = NanoVGManager.getFontId(fontName);
        if (fontId == -1) return;
        nvgFontFaceId(vg, fontId);
        nvgFontSize(vg, size);
        color(color, c1);
        nvgFillColor(vg, c1);
        nvgText(vg, x, y + 0.5f, text);
    }

    public void textCentered(String fontName, String text, float x, float y, float w, float h,
                              float size, int color) {
        float tw = textWidth(fontName, text, size);
        float tx = x + (w - tw) / 2f;
        float renderedSize = size * textScale;
        float ty = y + (h - renderedSize) / 2f;
        text(fontName, text, tx, ty, size, color);
    }

    public void textRight(String fontName, String text, float x, float y, float w,
                          float size, int color) {
        float tw = textWidth(fontName, text, size);
        text(fontName, text, x + w - tw, y, size, color);
    }

    // cached by (fontName + text + size) so the same string is not re-measured
    public float textWidth(String fontName, String text, float size) {
        return textWidthLiteral(fontName, AetherLang.localize(text), size * textScale);
    }

    public float textWidthLiteral(String fontName, String text, float size) {
        String key = fontName + "|" + text + "|" + size;
        if (key.equals(lastTextKey)) {
            return textWidthCache.getOrDefault(key, 0f);
        }
        int fontId = NanoVGManager.getFontId(fontName);
        if (fontId == -1) return 0f;
        nvgFontFaceId(vg, fontId);
        nvgFontSize(vg, size);
        float w = nvgTextBounds(vg, 0, 0, text, fontBounds);
        textWidthCache.put(key, w);
        lastTextKey = key;
        return w;
    }

    // existing line breaks are kept, long words are split; never returns empty
    public List<String> wrapTextToWidth(String fontName, String text, float size, float maxWidth) {
        text = AetherLang.localize(text);
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("");
            return lines;
        }
        if (maxWidth <= 0f) {
            lines.add(text);
            return lines;
        }

        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }

            String current = "";
            for (String word : paragraph.trim().split("\\s+")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (current.isEmpty() || textWidth(fontName, candidate, size) <= maxWidth) {
                    current = candidate;
                    continue;
                }

                lines.add(current);
                current = word;
                while (textWidth(fontName, current, size) > maxWidth && current.length() > 1) {
                    int split = fittingPrefixLength(fontName, current, size, maxWidth);
                    lines.add(current.substring(0, split));
                    current = current.substring(split);
                }
            }

            if (!current.isEmpty()) {
                lines.add(current);
            }
        }

        return lines.isEmpty() ? List.of(text) : lines;
    }

    private int fittingPrefixLength(String fontName, String text, float size, float maxWidth) {
        int low = 1;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (textWidth(fontName, text.substring(0, mid), size) <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return Math.max(1, low);
    }

    // -- Scissor / clipping ----------------------------------------------------

    // nested regions intersect automatically; always pair with popScissor()
    public void pushScissor(float x, float y, float w, float h) {
        scissorStack = new ScissorRegion(scissorStack, x, y, x + w, y + h);
        scissorStack.apply(vg);
    }

    // resets the scissor entirely when the stack empties
    public void popScissor() {
        nvgResetScissor(vg);
        scissorStack = scissorStack != null ? scissorStack.parent : null;
        if (scissorStack != null) scissorStack.apply(vg);
    }

    // single-level clip that skips the stack; clear it with resetScissor()
    public void scissor(float x, float y, float w, float h) {
        nvgScissor(vg, x, y, w, h);
    }

    public void resetScissor() {
        nvgResetScissor(vg);
    }

    // -- Transform state -------------------------------------------------------

    // pair with restore()
    public void save() { nvgSave(vg); }

    public void restore() { nvgRestore(vg); }

    public void translate(float dx, float dy) { nvgTranslate(vg, dx, dy); }

    public void scale(float sx, float sy) { nvgScale(vg, sx, sy); }

    public void skewX(float radians) { nvgSkewX(vg, radians); }

    // alpha is clamped to [0, 1]
    public void globalAlpha(float alpha) { nvgGlobalAlpha(vg, Math.max(0f, Math.min(1f, alpha))); }

    public void setTextScale(float s) {
        if (this.textScale != s) {
            this.textScale = s;
            textWidthCache.clear();
            lastTextKey = null;
        }
    }

    // -- Internal --------------------------------------------------------------

    // only valid until the next call using the same slot
    static void color(int argb, NVGColor out) {
        nvgRGBA(
                (byte) ((argb >> 16) & 0xFF),
                (byte) ((argb >>  8) & 0xFF),
                (byte) ( argb        & 0xFF),
                (byte) ((argb >> 24) & 0xFF),
                out
        );
    }

    // -- Nested scissor helper -------------------------------------------------

    private static final class ScissorRegion {
        final ScissorRegion parent;
        final float x1, y1, x2, y2;

        ScissorRegion(ScissorRegion parent, float x1, float y1, float x2, float y2) {
            this.parent = parent;
            // Intersect with parent region if present
            if (parent != null) {
                this.x1 = Math.max(x1, parent.x1);
                this.y1 = Math.max(y1, parent.y1);
                this.x2 = Math.min(x2, parent.x2);
                this.y2 = Math.min(y2, parent.y2);
            } else {
                this.x1 = x1; this.y1 = y1;
                this.x2 = x2; this.y2 = y2;
            }
        }

        void apply(long vg) {
            float w = Math.max(0f, x2 - x1);
            float h = Math.max(0f, y2 - y1);
            nvgScissor(vg, x1, y1, w, h);
        }
    }
}
