package dev.aether.renderer;

import dev.aether.util.AetherResources;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NSVGImage;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoSVG;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

// rasterized at device resolution (canvas transform included) and cached per "path@physWxphysH", so high-dpi and
// scaled canvases stay crisp; tinting works on any fill, white included, by overriding the paint's colors
final class SVGRenderer {

    private static final Pattern SVG_LENGTH_PATTERN = Pattern.compile(
            "^\\s*([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([a-zA-Z%]*)\\s*$"
    );

    // every distinct size is a raster and a gl texture, so scale animations would otherwise grow the cache forever
    private static final int MAX_RASTERS = 256;

    // failed loads stay cached as 0 so a missing file is not re-read every frame
    private static final Map<String, Integer> imageCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            if (size() <= MAX_RASTERS) return false;
            retire(eldest.getValue());
            return true;
        }
    };

    private static final float[] transform = new float[6];

    private SVGRenderer() {}

    // -- Render ----------------------------------------------------------------

    // argbColor alpha is opacity; rgb tints white-filled svgs
    static void render(long vg, String resourcePath,
                       float x, float y, float width, float height,
                       int argbColor, NVGPaint paintScratch) {

        NanoVG.nvgCurrentTransform(vg, transform);
        float px = NanoVGManager.getPxRatio();
        int physW = bucket(width * (float) Math.hypot(transform[0], transform[1]) * px);
        int physH = bucket(height * (float) Math.hypot(transform[2], transform[3]) * px);

        String cacheKey = resourcePath + "@" + physW + "x" + physH;
        int imgHandle = getOrLoad(vg, cacheKey, resourcePath, physW, physH);
        if (imgHandle <= 0) return;

        float alpha = ((argbColor >> 24) & 0xFF) / 255f;
        float r     = ((argbColor >> 16) & 0xFF) / 255f;
        float g     = ((argbColor >>  8) & 0xFF) / 255f;
        float b     = ( argbColor        & 0xFF) / 255f;

        NanoVG.nvgSave(vg);
        NanoVG.nvgBeginPath(vg);
        NanoVG.nvgRect(vg, x, y, width, height);

        // nvgImagePattern sets innerColor to (1,1,1,alpha). Override with actual tint.
        NanoVG.nvgImagePattern(vg, x, y, width, height, 0f, imgHandle, alpha, paintScratch);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor tintColor = NVGColor.malloc(stack);
            NanoVG.nvgRGBAf(r, g, b, alpha, tintColor);
            NVGPaint.ninnerColor(paintScratch.address(), tintColor);
            NVGPaint.nouterColor(paintScratch.address(), tintColor);
        }

        NanoVG.nvgFillPaint(vg, paintScratch);
        NanoVG.nvgFill(vg);
        NanoVG.nvgRestore(vg);
    }

    // -- Cache -----------------------------------------------------------------

    // device sizes round up to even pixels, so fractional ui scales and scale animations share a few rasters
    static int bucket(float devicePixels) {
        int pixels = Math.max(1, (int) Math.ceil(devicePixels - 1e-3f));
        return (pixels + 1) & ~1;
    }

    static int cachedRasters() {
        return imageCache.size();
    }

    private static int getOrLoad(long vg, String cacheKey, String resourcePath, int width, int height) {
        Integer cached = imageCache.get(cacheKey);
        if (cached != null) return cached;

        int handle = Math.max(0, loadSVG(vg, resourcePath, width, height));
        imageCache.put(cacheKey, handle);
        return handle;
    }

    // nanovg only draws at nvgEndFrame, so an image evicted mid-frame may still be sampled by this frame's fills
    private static void retire(int handle) {
        if (handle > 0) NanoVGManager.runInNextFrame(() -> NanoVG.nvgDeleteImage(NanoVGManager.getVg(), handle));
    }

    private static int loadSVG(long vg, String resourcePath, int width, int height) {
        // -- 1. Read SVG as String ---------------------------------------------
        String svgText;
        try (InputStream in = AetherResources.open(resourcePath)) {
            if (in == null) {
                System.err.println("[Aether] SVG not found: " + resourcePath);
                return -1;
            }
            svgText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Aether] IOException reading SVG " + resourcePath + ": " + e.getMessage());
            return -1;
        }

        // -- 2. Parse ----------------------------------------------------------
        NSVGImage svgImage = NanoSVG.nsvgParse(svgText, "px", 96f);
        if (svgImage == null) {
            System.err.println("[Aether] NanoSVG parse failed: " + resourcePath);
            return -1;
        }

        float[] svgSize = readSvgSize(svgText);
        float svgW = svgSize[0];
        float svgH = svgSize[1];
        if (svgW <= 0f || svgH <= 0f) {
            NanoSVG.nsvgDelete(svgImage);
            System.err.println("[Aether] SVG has no usable size metadata: " + resourcePath);
            return -1;
        }

        // -- 3. Rasterize at physical pixel dimensions --------------------------
        long rasterizer = NanoSVG.nsvgCreateRasterizer();
        if (rasterizer == MemoryUtil.NULL) {
            NanoSVG.nsvgDelete(svgImage);
            System.err.println("[Aether] NanoSVG could not create rasterizer");
            return -1;
        }

        float scale = (svgW > 0 && svgH > 0)
                ? Math.min((float) width / svgW, (float) height / svgH)
                : 1f;
        int rW = Math.max(1, (int) (svgW * scale));
        int rH = Math.max(1, (int) (svgH * scale));

        ByteBuffer pixels = MemoryUtil.memAlloc(rW * rH * 4);
        NanoSVG.nsvgRasterize(rasterizer, svgImage, 0f, 0f, scale, pixels, rW, rH, rW * 4);

        NanoSVG.nsvgDeleteRasterizer(rasterizer);
        NanoSVG.nsvgDelete(svgImage);

        // -- 4. Upload to GPU --------------------------------------------------
        int nvgId = NanoVG.nvgCreateImageRGBA(vg, rW, rH, 0, pixels);
        MemoryUtil.memFree(pixels);

        // nanovg returns 0 when it cannot create the image
        if (nvgId <= 0) {
            System.err.println("[Aether] NanoVG image creation failed: " + resourcePath);
            return -1;
        }
        return nvgId;
    }

    private static float[] readSvgSize(String svgText) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setCoalescing(true);
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // Best-effort hardening; safe to continue without it for local bundled assets.
            }

            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svgText)));
            Element root = document.getDocumentElement();
            if (root == null || !"svg".equalsIgnoreCase(root.getLocalName() != null ? root.getLocalName() : root.getTagName())) {
                return new float[]{-1f, -1f};
            }

            Float width = parseSvgLength(root.getAttribute("width"));
            Float height = parseSvgLength(root.getAttribute("height"));
            if (width != null && width > 0f && height != null && height > 0f) {
                return new float[]{width, height};
            }

            String viewBox = root.getAttribute("viewBox");
            if (!viewBox.isBlank()) {
                String[] parts = viewBox.trim().split("[,\\s]+");
                if (parts.length == 4) {
                    float vbWidth = Float.parseFloat(parts[2]);
                    float vbHeight = Float.parseFloat(parts[3]);
                    if (vbWidth > 0f && vbHeight > 0f) {
                        return new float[]{vbWidth, vbHeight};
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the invalid marker below.
        }
        return new float[]{-1f, -1f};
    }

    private static Float parseSvgLength(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        Matcher matcher = SVG_LENGTH_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return null;
        }

        float magnitude = Float.parseFloat(matcher.group(1));
        String unit = matcher.group(2).toLowerCase();
        return switch (unit) {
            case "", "px" -> magnitude;
            case "pt" -> magnitude * (96f / 72f);
            case "pc" -> magnitude * 16f;
            case "mm" -> magnitude * (96f / 25.4f);
            case "cm" -> magnitude * (96f / 2.54f);
            case "in" -> magnitude * 96f;
            default -> null;
        };
    }

    // -- Lifecycle -------------------------------------------------------------

    // call when destroying the nanovg context
    static void destroy(long vg) {
        for (int handle : imageCache.values()) {
            if (handle > 0) NanoVG.nvgDeleteImage(vg, handle);
        }
        imageCache.clear();
    }
}

