package dev.aether.renderer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.aether.renderer.canvas.Canvas;
import dev.aether.renderer.canvas.Canvas.Paint;
import dev.aether.util.AetherLang;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

// only safe to call between NanoVGManager.beginFrame and endFrame; get it from NanoVGManager.getRenderer()
// coords are logical pixels and colors are 0xAARRGGBB ints; it draws through Canvas, so vulkan works too
public class NVGRenderer {

    private final Canvas canvas;
    private final Font.GlyphVisitor minecraftText;
    private final Map<Integer, Image> images = new HashMap<>();
    private int nextImage = 1;

    private record Image(GpuTexture texture, GpuTextureView view) {}

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

    NVGRenderer(Canvas canvas) {
        this.canvas = canvas;
        this.minecraftText = new Font.GlyphVisitor() {
            @Override public void acceptGlyph(TextRenderable.Styled glyph) { canvas.minecraftGlyph(glyph); }
            @Override public void acceptEffect(TextRenderable effect) { canvas.minecraftGlyph(effect); }
        };
    }

    Canvas canvas() { return canvas; }

    void beginFrame() {
        scissorStack = null;
    }

    public void minecraftText(Font.PreparedText text) {
        text.visit(minecraftText);
    }

    // -- Basic shapes ----------------------------------------------------------

    public void rect(float x, float y, float w, float h, int color) {
        canvas.fill(canvas.path().rect(x, y, w, h), Paint.solid(color));
    }

    public void roundedRect(float x, float y, float w, float h, float radius, int color) {
        canvas.fill(canvas.path().roundedRect(x, y, w, h, radius), Paint.solid(color));
    }


    public void rectOutline(float x, float y, float w, float h, float radius, float thickness, int color) {
        float half = thickness / 2f;
        float r = Math.min(radius, Math.min(w, h) / 2f);

        if (r < 0.5f) {
            // Sharp corners: four non-overlapping filled rects
            rect(x - half, y - half, w + thickness, thickness, color);
            rect(x - half, y + h - half, w + thickness, thickness, color);
            rect(x - half, y + half, thickness, h - thickness, color);
            rect(x + w - half, y + half, thickness, h - thickness, color);
            return;
        }

        // one open path from the top midpoint, tangent-continuous at every arc join so alpha stays uniform
        canvas.stroke(fullOutline(x, y, w, h, r), Paint.solid(color), thickness);
    }

    private Canvas.Path fullOutline(float x, float y, float w, float h, float r) {
        return canvas.path()
                .moveTo(x + w / 2f, y)
                .lineTo(x + w - r, y)
                .arc(x + w - r, y + r, r, HALF_PI * 3, HALF_PI * 4, true)
                .lineTo(x + w, y + h - r)
                .arc(x + w - r, y + h - r, r, 0, HALF_PI, true)
                .lineTo(x + r, y + h)
                .arc(x + r, y + h - r, r, HALF_PI, HALF_PI * 2, true)
                .lineTo(x, y + r)
                .arc(x + r, y + r, r, HALF_PI * 2, HALF_PI * 3, true)
                .lineTo(x + w / 2f, y);
    }

    // corners and verticals only, as two open subpaths
    private Canvas.Path sideOutline(float x, float y, float w, float h, float r) {
        return canvas.path()
                .moveTo(x + r, y)
                .arc(x + r, y + r, r, HALF_PI * 3, HALF_PI * 2, false)
                .lineTo(x, y + h - r)
                .arc(x + r, y + h - r, r, HALF_PI * 2, HALF_PI, false)
                .moveTo(x + w - r, y)
                .arc(x + w - r, y + r, r, HALF_PI * 3, HALF_PI * 4, true)
                .lineTo(x + w, y + h - r)
                .arc(x + w - r, y + h - r, r, 0, HALF_PI, true);
    }

    // bakes alpha into rgb premultiplied against black so the stroke is fully opaque, which assumes a dark background
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
            // the scissor stops at y+half so the horizontal stroke centred on y stays clipped
            float pad = h / 2f - (ratio / 0.4f) * (h / 2f - half);
            canvas.save();
            canvas.intersectScissor(x - half, y + pad, w + thickness, h - pad * 2f);
            rectOutlineSolid(x, y, w, h, radius, thickness, color);
            canvas.restore();
        } else {
            // corners and verticals as a stroke, then horizontal rects growing from each corner
            canvas.stroke(sideOutline(x, y, w, h, r), Paint.solid(c), thickness);

            float segLen = ((ratio - 0.4f) / 0.6f) * (w / 2f - r);
            rect(x + r,              y - half, segLen, thickness, c);
            rect(x + w - r - segLen, y - half, segLen, thickness, c);
            rect(x + r,              y + h - half, segLen, thickness, c);
            rect(x + w - r - segLen, y + h - half, segLen, thickness, c);
        }
    }

    // same, stroked with a top-left to bottom-right gradient
    public void rectOutlineVerticalSidesGradient(float x, float y, float w, float h, float radius,
                                                   float thickness, int colorTL, int colorBR, float ratio) {
        if (ratio <= 0f) return;
        Paint paint = Paint.linear(x, y, x + w, y + h, opaqueFromAlpha(colorTL), opaqueFromAlpha(colorBR));
        float r    = Math.min(radius, Math.min(w, h) / 2f);
        float half = thickness / 2f;

        if (ratio >= 1f) {
            canvas.stroke(fullOutline(x, y, w, h, r), paint, thickness);
        } else if (ratio <= 0.4f) {
            float pad = h / 2f - (ratio / 0.4f) * (h / 2f - half);
            canvas.save();
            canvas.intersectScissor(x - half, y + pad, w + thickness, h - pad * 2f);
            canvas.stroke(fullOutline(x, y, w, h, r), paint, thickness);
            canvas.restore();
        } else {
            canvas.stroke(sideOutline(x, y, w, h, r), paint, thickness);
            float segLen = ((ratio - 0.4f) / 0.6f) * (w / 2f - r);
            canvas.fill(canvas.path()
                    .rect(x + r,              y - half,     segLen, thickness)
                    .rect(x + w - r - segLen, y - half,     segLen, thickness)
                    .rect(x + r,              y + h - half, segLen, thickness)
                    .rect(x + w - r - segLen, y + h - half, segLen, thickness), paint);
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
        canvas.fill(canvas.path().circle(cx, cy, radius), Paint.solid(color));
    }

    public void line(float x1, float y1, float x2, float y2, float thickness, int color) {
        canvas.stroke(canvas.path().moveTo(x1, y1).lineTo(x2, y2), Paint.solid(color), thickness);
    }

    public void polyline(float[] points, int count, float thickness, int color) {
        if (count < 2) return;
        Canvas.Path path = canvas.path().moveTo(points[0], points[1]);
        for (int i = 1; i < count; i++) path.lineTo(points[i * 2], points[i * 2 + 1]);
        canvas.stroke(path, Paint.solid(color), thickness);
    }

    // -- Gradients -------------------------------------------------------------

    public void linearGradient(float x, float y, float w, float h, float radius,
                                int colorTop, int colorBottom) {
        canvas.fill(canvas.path().roundedRect(x, y, w, h, radius), Paint.linear(x, y, x, y + h, colorTop, colorBottom));
    }

    public void horizontalGradient(float x, float y, float w, float h, float radius,
                                    int colorLeft, int colorRight) {
        canvas.fill(canvas.path().roundedRect(x, y, w, h, radius), Paint.linear(x, y, x + w, y, colorLeft, colorRight));
    }

    // -- Shadow / Glow ---------------------------------------------------------

    // draw this before the element it belongs to so it lands underneath
    public void shadow(float x, float y, float w, float h, float radius, float blur, int color) {
        canvas.fillBoxGradient(canvas.path().rect(x - blur, y - blur * 0.5f, w + blur * 2, h + blur * 2),
                x, y + blur * 0.5f, w, h, radius + blur * 0.5f, blur, color, color & 0x00FFFFFF);
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

            canvas.fillBoxGradient(canvas.path().rect(x - spread, y - spread, w + spread * 2, h + spread * 2),
                    x, y, w, h, radius, feather, (layerAlpha << 24) | (color & 0x00FFFFFF), color & 0x00FFFFFF);
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

            canvas.fillRadialGradient(cx, cy, radius * 0.5f, radius + spread,
                    (layerAlpha << 24) | (color & 0x00FFFFFF), color & 0x00FFFFFF);
        }

        circle(cx, cy, radius, color);
    }

    // an approximation, not a real gaussian - it composites a semi-transparent overlay that suggests frosting

    public void blur(float x, float y, float w, float h, float blurRadius) {
        float clampedBlur = Math.min(blurRadius, 30f);
        rect(x, y, w, h, 0x40000000);
        shadow(x, y, w, h, 0f, clampedBlur, 0x28000000);
        rect(x, y, w, h, 0x0CFFFFFF);
    }

    // everything is clipped to the rounded shape, so nothing bleeds past the corners
    public void blur(float x, float y, float w, float h, float radius, float blurRadius) {
        float clampedBlur = Math.min(blurRadius, 30f);
        roundedRect(x, y, w, h, radius, 0x40000000);
        canvas.fillBoxGradient(canvas.path().roundedRect(x, y, w, h, radius),
                x, y + clampedBlur * 0.5f, w, h, radius + clampedBlur * 0.5f, clampedBlur, 0x28000000, 0x00000000);
        roundedRect(x, y, w, h, radius, 0x0CFFFFFF);
    }


    // same approximation as blur(), circular
    public void blurCircle(float cx, float cy, float radius, float blurRadius) {
        // keep the weight capped so overdraw stays cheap
        float b = Math.min(Math.max(blurRadius, 0f), 30f);

        circle(cx, cy, radius, 0x40000000);

        // outward circles with fading alpha stand in for the soft edge of shadow()
        final int STEPS = 5;
        final int MAX_A = 0x28;
        for (int i = 1; i <= STEPS; i++) {
            float t  = i / (float) STEPS;
            float rr = radius + t * b;
            int a    = Math.round(MAX_A * (1f - t));
            circle(cx, cy, rr, (a & 0xFF) << 24);
        }

        circle(cx, cy, radius, 0x0CFFFFFF);
    }


    public void blurRingApprox(float cx, float cy, float radius, float blurRadius, float alpha) {
        float b = Math.min(Math.max(blurRadius, 0f), 30f);
        final int STEPS = 5;
        int maxA = Math.round(Math.max(0f, Math.min(alpha, 1f)) * 0x28);

        for (int i = 1; i <= STEPS; i++) {
            float t  = i / (float) STEPS;
            float rr = radius + t * b;
            int a    = Math.round(maxA * (1f - t));
            circle(cx, cy, rr, (a & 0xFF) << 24);
        }
    }

    public void circleOutline(float cx, float cy, float radius, float strokeWidth, int color) {
        canvas.stroke(canvas.path().circle(cx, cy, Math.max(0.5f, radius)), Paint.solid(color), Math.max(0.5f, strokeWidth));
    }


    // -- SVG rendering ---------------------------------------------------------

    // svgs are parsed, rasterized into the ui atlas on first use; color 0 means no tint
    public void renderSVG(String resourcePath, float x, float y, float width, float height, int color) {
        SVGRenderer.render(canvas, resourcePath, x, y, width, height, color);
    }

    public int createImageRGBA(int width, int height, ByteBuffer pixels) {
        if (width <= 0 || height <= 0) return -1;
        var device = RenderSystem.getDevice();
        GpuTexture texture = device.createTexture(() -> "Aether image", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        device.createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, 0, width, height);
        int handle = nextImage++;
        images.put(handle, new Image(texture, device.createTextureView(texture)));
        return handle;
    }

    public void deleteImage(int handle) {
        Image image = images.remove(handle);
        if (image != null) {
            image.view.close();
            image.texture.close();
        }
    }

    void deleteAllImages() {
        for (Image image : images.values()) {
            image.view.close();
            image.texture.close();
        }
        images.clear();
    }

    public void image(int handle, float x, float y, float width, float height, float radius, float alpha) {
        Image image = images.get(handle);
        if (image == null) return;
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        canvas.image(image.view, x, y, width, height, radius, (a << 24) | 0x00FFFFFF);
    }

    // -- Text ------------------------------------------------------------------

    public void text(String fontName, String text, float x, float y, float size, int color) {
        textLiteral(fontName, AetherLang.localize(text), x, y, size * textScale, color);
    }

    // Server text must not be localized or inherit the settings menu's text scale.
    public void textLiteral(String fontName, String text, float x, float y, float size, int color) {
        int fontId = canvas.fontId(fontName);
        if (fontId == -1) return;
        canvas.text(fontId, text, x, y + 0.5f, size, color);
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
        int fontId = canvas.fontId(fontName);
        if (fontId == -1) return 0f;
        float w = canvas.textWidth(fontId, text, size);
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
        scissorStack.apply(canvas);
    }

    // resets the scissor entirely when the stack empties
    public void popScissor() {
        canvas.resetScissor();
        scissorStack = scissorStack != null ? scissorStack.parent : null;
        if (scissorStack != null) scissorStack.apply(canvas);
    }

    // single-level clip that skips the stack; clear it with resetScissor()
    public void scissor(float x, float y, float w, float h) {
        canvas.scissor(x, y, w, h);
    }

    public void resetScissor() {
        canvas.resetScissor();
    }

    // -- Transform state -------------------------------------------------------

    // pair with restore()
    public void save() { canvas.save(); }

    public void restore() { canvas.restore(); }

    public void translate(float dx, float dy) { canvas.translate(dx, dy); }

    public void scale(float sx, float sy) { canvas.scale(sx, sy); }

    public void skewX(float radians) { canvas.skewX(radians); }

    // alpha is clamped to [0, 1]
    public void globalAlpha(float alpha) { canvas.globalAlpha(Math.max(0f, Math.min(1f, alpha))); }

    public void setTextScale(float s) {
        if (this.textScale != s) {
            this.textScale = s;
            textWidthCache.clear();
            lastTextKey = null;
        }
    }

    // -- Nested scissor helper -------------------------------------------------

    private static final class ScissorRegion {
        final ScissorRegion parent;
        final float x1, y1, x2, y2;

        ScissorRegion(ScissorRegion parent, float x1, float y1, float x2, float y2) {
            this.parent = parent;
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

        void apply(Canvas canvas) {
            float w = Math.max(0f, x2 - x1);
            float h = Math.max(0f, y2 - y1);
            canvas.scissor(x1, y1, w, h);
        }
    }
}
