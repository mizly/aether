package dev.aether.renderer;

import dev.aether.renderer.canvas.Canvas;
import dev.aether.util.AetherResources;
import org.lwjgl.nanovg.NSVGImage;
import org.lwjgl.nanovg.NanoSVG;
import org.lwjgl.system.MemoryUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

// rasterized at physical resolution and cached per "path@physWxphysH", so high-dpi stays crisp
// nanosvg only rasterizes on the cpu, so it works on vulkan; the tint multiplies every texel
final class SVGRenderer {

    private static final Pattern SVG_LENGTH_PATTERN = Pattern.compile(
            "^\\s*([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([a-zA-Z%]*)\\s*$"
    );

    private static final Map<String, Canvas.AtlasImage> imageCache = new HashMap<>();

    private SVGRenderer() {}

    // -- Render ----------------------------------------------------------------

    // argbColor alpha is opacity; rgb tints white-filled svgs
    static void render(Canvas canvas, String resourcePath, float x, float y, float width, float height, int argbColor) {
        float px  = NanoVGManager.getPxRatio();
        int   physW = Math.max(1, (int) Math.ceil(width  * px));
        int   physH = Math.max(1, (int) Math.ceil(height * px));

        String cacheKey = resourcePath + "@" + physW + "x" + physH;
        Canvas.AtlasImage image = imageCache.get(cacheKey);
        if (!canvas.isCurrent(image)) {
            image = loadSVG(canvas, resourcePath, physW, physH);
            if (image == null) return;
            imageCache.put(cacheKey, image);
        }
        canvas.drawAtlasImage(image, x, y, width, height, argbColor);
    }

    // -- Cache -----------------------------------------------------------------

    private static Canvas.AtlasImage loadSVG(Canvas canvas, String resourcePath, int width, int height) {
        String svgText;
        try (InputStream in = AetherResources.open(resourcePath)) {
            if (in == null) {
                System.err.println("[Aether] SVG not found: " + resourcePath);
                return null;
            }
            svgText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Aether] IOException reading SVG " + resourcePath + ": " + e.getMessage());
            return null;
        }

        NSVGImage svgImage = NanoSVG.nsvgParse(svgText, "px", 96f);
        if (svgImage == null) {
            System.err.println("[Aether] NanoSVG parse failed: " + resourcePath);
            return null;
        }

        float[] svgSize = readSvgSize(svgText);
        float svgW = svgSize[0];
        float svgH = svgSize[1];
        if (svgW <= 0f || svgH <= 0f) {
            NanoSVG.nsvgDelete(svgImage);
            System.err.println("[Aether] SVG has no usable size metadata: " + resourcePath);
            return null;
        }

        long rasterizer = NanoSVG.nsvgCreateRasterizer();
        if (rasterizer == MemoryUtil.NULL) {
            NanoSVG.nsvgDelete(svgImage);
            System.err.println("[Aether] NanoSVG could not create rasterizer");
            return null;
        }

        float scale = Math.min((float) width / svgW, (float) height / svgH);
        int rW = Math.max(1, (int) (svgW * scale));
        int rH = Math.max(1, (int) (svgH * scale));

        ByteBuffer pixels = MemoryUtil.memAlloc(rW * rH * 4);
        try {
            NanoSVG.nsvgRasterize(rasterizer, svgImage, 0f, 0f, scale, pixels, rW, rH, rW * 4);
            Canvas.AtlasImage image = canvas.uploadToAtlas(pixels, rW, rH);
            if (image == null) System.err.println("[Aether] SVG too large for the UI atlas: " + resourcePath);
            return image;
        } finally {
            MemoryUtil.memFree(pixels);
            NanoSVG.nsvgDeleteRasterizer(rasterizer);
            NanoSVG.nsvgDelete(svgImage);
        }
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

    // atlas entries die with the canvas
    static void destroy() {
        imageCache.clear();
    }
}
