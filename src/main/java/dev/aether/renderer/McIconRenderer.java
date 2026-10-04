package dev.aether.renderer;

import org.lwjgl.nanovg.NVGPaint;

import java.util.List;

import static org.lwjgl.nanovg.NanoVG.*;

// paints McIcons inside a nanovg frame on the render thread; opacity rides on the paint colour, never on globalAlpha,
// so icons fade correctly inside callers' own fades
final class McIconRenderer {

    // vanilla's glint adds its streak texture squared: on average about 0x1E0750, with streaks up to 0x6E1CFF.
    // approximated by a constant wash plus a soft sheen sweeping down the icon
    private static final int GLINT_RGB = 0x8030FF;
    private static final float GLINT_WASH = 0.25f;
    private static final float GLINT_SHEEN = 0.5f;
    private static final float GLINT_SWEEP_SECONDS = 2.4f;
    private static final float GLINT_SHEEN_HALF_HEIGHT = 0.3f;
    private static final int GLINT_STRIPS = 12;

    private static final float[] transform = new float[6];

    private McIconRenderer() {}

    static void draw(long vg, NVGPaint paint, McIcon icon, float x, float y, float size, int tint,
                     boolean glint, float timeSeconds) {
        if (icon == null || size <= 0f || (tint >>> 24) == 0) return;
        McIcon drawable = McIcons.drawable(icon);
        if (drawable == null) return;
        List<IsoBlockPainter.Face> faces = IsoBlockPainter.faces(drawable);
        if (faces.isEmpty()) return;

        float pixelsPerUnit = pixelsPerUnit(vg);
        if (pixelsPerUnit > 0f) {
            float snapped = snap(size, pixelsPerUnit);
            float ratio = NanoVGManager.getPxRatio();
            x = onDevicePixel(x + (size - snapped) / 2f, transform[0], transform[4], ratio);
            y = onDevicePixel(y + (size - snapped) / 2f, transform[3], transform[5], ratio);
            size = snapped;
        }

        nvgSave(vg);
        // antialiased fringes would leave seams between faces and sample texels outside each face
        nvgShapeAntiAlias(vg, false);
        paint(vg, paint, faces, x, y, size, tint, false);
        if (glint) glint(vg, paint, faces, x, y, size, (tint >>> 24) / 255f, timeSeconds);
        nvgRestore(vg);
    }

    static float snappedSize(long vg, float size) {
        float pixelsPerUnit = pixelsPerUnit(vg);
        return pixelsPerUnit > 0f && size > 0f ? snap(size, pixelsPerUnit) : size;
    }

    // device pixels per local unit when the transform only scales uniformly and translates, else 0 (no snapping)
    private static float pixelsPerUnit(long vg) {
        nvgCurrentTransform(vg, transform);
        float scale = transform[0];
        if (scale <= 0f || Math.abs(transform[1]) > 1e-5f || Math.abs(transform[2]) > 1e-5f
                || Math.abs(transform[3] - scale) > scale * 1e-4f) {
            return 0f;
        }
        return scale * NanoVGManager.getPxRatio();
    }

    // 16px art stays crisp when every texel covers the same whole number of device pixels; smaller icons only round
    private static float snap(float size, float pixelsPerUnit) {
        float pixels = size * pixelsPerUnit;
        float snapped = pixels >= 16f ? Math.round(pixels / 16f) * 16f : Math.max(1f, Math.round(pixels));
        return snapped / pixelsPerUnit;
    }

    private static float onDevicePixel(float local, float scale, float offset, float ratio) {
        return (Math.round((local * scale + offset) * ratio) / ratio - offset) / scale;
    }

    private static void paint(long vg, NVGPaint paint, List<IsoBlockPainter.Face> faces, float x, float y, float size,
                              int tint, boolean mask) {
        for (IsoBlockPainter.Face face : faces) {
            McTextures.Texture texture = mask ? McTextures.mask(face.texture()) : McTextures.get(face.texture());
            if (texture.missing()) continue;
            int color = mask ? tint : multiply(face.color(), tint);
            nvgSave(vg);
            nvgTranslate(vg, x, y);
            nvgScale(vg, size, size);
            nvgTransform(vg, face.a(), face.b(), face.c(), face.d(), face.e(), face.f());
            nvgImagePattern(vg, 0f, 0f, 1f, 1f, 0f, texture.handle(), 1f, paint);
            NVGRenderer.color(color, paint.innerColor());
            NVGRenderer.color(color, paint.outerColor());
            nvgBeginPath(vg);
            nvgRect(vg, face.u0(), face.v0(), face.u1() - face.u0(), face.v1() - face.v0());
            nvgFillPaint(vg, paint);
            nvgFill(vg);
            nvgRestore(vg);
        }
    }

    // additive passes over a white copy of each texture, so the sheen follows the item's shape and not its colours;
    // the sheen is full-width strips because only axis-aligned scissors intersect exactly with the caller's clip
    private static void glint(long vg, NVGPaint paint, List<IsoBlockPainter.Face> faces, float x, float y, float size,
                              float alpha, float timeSeconds) {
        nvgGlobalCompositeOperation(vg, NVG_LIGHTER);
        float wash = GLINT_WASH * (0.85f + 0.15f * (float) Math.sin(timeSeconds * 2.1f));
        paint(vg, paint, faces, x, y, size, glintColor(wash * alpha), true);

        float phase = (timeSeconds / GLINT_SWEEP_SECONDS) % 1f;
        if (phase < 0f) phase += 1f;
        float centre = phase * (1f + 2f * GLINT_SHEEN_HALF_HEIGHT) - GLINT_SHEEN_HALF_HEIGHT;
        float stripHeight = 1f / GLINT_STRIPS;
        for (int strip = 0; strip < GLINT_STRIPS; strip++) {
            float distance = Math.abs((strip + 0.5f) * stripHeight - centre) / GLINT_SHEEN_HALF_HEIGHT;
            if (distance >= 1f) continue;
            float strength = 0.5f + 0.5f * (float) Math.cos(distance * Math.PI);
            nvgSave(vg);
            nvgIntersectScissor(vg, x, y + strip * stripHeight * size, size, stripHeight * size);
            paint(vg, paint, faces, x, y, size, glintColor(GLINT_SHEEN * strength * alpha), true);
            nvgRestore(vg);
        }
    }

    private static int glintColor(float intensity) {
        int alpha = Math.round(Math.clamp(intensity, 0f, 1f) * 255f);
        return (alpha << 24) | GLINT_RGB;
    }

    private static int multiply(int a, int b) {
        int alpha = ((a >>> 24) * (b >>> 24) + 127) / 255;
        int red = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) + 127) / 255;
        int green = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) + 127) / 255;
        int blue = ((a & 0xFF) * (b & 0xFF) + 127) / 255;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
}
