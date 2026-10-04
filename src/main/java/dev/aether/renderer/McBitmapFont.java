package dev.aether.renderer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.ui.util.Fonts;
import it.unimi.dsi.fastutil.floats.Float2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.lwjgl.nanovg.NanoVG.*;

// minecraft's gui font drawn as nearest-filtered nanovg quads from the game's own font json and textures, resource
// packs included, with vanilla's advances, shadow, bold and § codes; code points no bitmap covers fall back to inter
public final class McBitmapFont {

    public static final int LINE_HEIGHT = 9;

    public static final char SECTION = '§';

    public static final String ELLIPSIS = "...";

    private static final String CODES = "0123456789abcdefklmnor";
    private static final int[] COLOURS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private static final int OBFUSCATED = 1;
    private static final int BOLD = 1 << 1;
    private static final int STRIKETHROUGH = 1 << 2;
    private static final int UNDERLINE = 1 << 3;
    private static final int ITALIC = 1 << 4;

    // glyph tops are measured from the line top, and vanilla puts the baseline 7 font pixels down
    private static final int BASELINE = 7;
    // inter at this size lands its caps and cjk ideographs close to the bitmap glyph heights
    private static final float FALLBACK_SIZE = 9f;
    private static final float FALLBACK_ESTIMATE = 6f;
    private static final int MAX_LAYOUTS = 512;

    // a glyph cell in a font texture; texture is null for space-provider glyphs, which only advance
    record Glyph(String texture, int u, int v, int width, int height, float pixelScale, float advance, float top) {}

    record Table(Int2ObjectMap<Glyph> glyphs, Float2ObjectOpenHashMap<Glyph[]> byAdvance) {}

    private record LayoutKey(String text, boolean formatted, int scale) {}

    // positions are local units from the text origin; colour -1 means the caller's colour
    private record Layout(Glyph[] glyphs, int[] codepoints, float[] x, int[] colours, int[] styles,
                          List<Line> lines, float width) {}

    private record Line(float x0, float x1, float y, int colour) {}

    private static volatile Table table;

    private static final Map<LayoutKey, Layout> layouts = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LayoutKey, Layout> eldest) {
            return size() > MAX_LAYOUTS;
        }
    };

    private static final float[] transform = new float[6];
    private static final float[] bounds = new float[4];

    private McBitmapFont() {}

    public static int lineHeight(int scale) {
        return LINE_HEIGHT * scale;
    }

    // § codes take no space; scale is local units per font pixel
    public static float width(String text, int scale) {
        return layout(text, true, scale).width();
    }

    // user text: § is a glyph like any other
    public static float widthLiteral(String text, int scale) {
        return layout(text, false, scale).width();
    }

    public static String strip(String text) {
        if (text == null || text.indexOf(SECTION) < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    // word wrap that keeps "\n" breaks and starts each continued line with the colour and styles still in effect
    public static List<String> wrap(String text, float maxWidth, int scale) {
        return wrap(text, maxWidth, scale, true);
    }

    public static List<String> wrapLiteral(String text, float maxWidth, int scale) {
        return wrap(text, maxWidth, scale, false);
    }

    // the longest prefix that fits, ending in "..." when anything was cut
    public static String ellipsize(String text, float maxWidth, int scale) {
        return ellipsize(text, maxWidth, scale, true);
    }

    public static String ellipsizeLiteral(String text, float maxWidth, int scale) {
        return ellipsize(text, maxWidth, scale, false);
    }

    // any thread; glyphs and metrics reload from the current resource packs on next use
    public static void invalidate() {
        table = null;
        synchronized (layouts) {
            layouts.clear();
        }
    }

    // -- drawing --------------------------------------------------------------

    // inside a nanovg frame; returns the advance in local units
    static float draw(long vg, NVGPaint paint, String text, float x, float y, int scale, int color, boolean shadow,
                      boolean formatted) {
        if (text == null || text.isEmpty() || scale <= 0) return 0f;
        Layout layout = layout(text, formatted, scale);
        if ((color >>> 24) == 0 || layout.glyphs().length == 0) return layout.width();
        if (snapsToDevicePixels(vg)) {
            float ratio = NanoVGManager.getPxRatio();
            x = onDevicePixel(x, transform[0], transform[4], ratio);
            y = onDevicePixel(y, transform[3], transform[5], ratio);
        }
        nvgSave(vg);
        // antialiased fringes would sample the neighbouring glyph cells
        nvgShapeAntiAlias(vg, false);
        if (shadow) pass(vg, paint, layout, x + scale, y + scale, scale, color, true);
        pass(vg, paint, layout, x, y, scale, color, false);
        nvgRestore(vg);
        return layout.width();
    }

    private static void pass(long vg, NVGPaint paint, Layout layout, float x, float y, int scale, int color,
                             boolean shadow) {
        Table glyphs = table();
        for (int i = 0; i < layout.glyphs().length; i++) {
            int argb = colour(layout.colours()[i], color, shadow);
            int style = layout.styles()[i];
            Glyph glyph = layout.glyphs()[i];
            if (glyph != null && glyph.texture() != null && (style & OBFUSCATED) != 0) glyph = obfuscated(glyphs, glyph);
            float gx = x + layout.x()[i];
            boolean italic = (style & ITALIC) != 0;
            glyph(vg, paint, glyph, layout.codepoints()[i], gx, y, scale, argb, italic);
            if ((style & BOLD) != 0) glyph(vg, paint, glyph, layout.codepoints()[i], gx + scale, y, scale, argb, italic);
        }
        for (Line line : layout.lines()) {
            nvgBeginPath(vg);
            nvgRect(vg, x + line.x0(), y + line.y() * scale, line.x1() - line.x0(), scale);
            NVGRenderer.color(colour(line.colour(), color, shadow), paint.innerColor());
            nvgFillColor(vg, paint.innerColor());
            nvgFill(vg);
        }
    }

    private static void glyph(long vg, NVGPaint paint, Glyph glyph, int codepoint, float x, float y, int scale,
                              int argb, boolean italic) {
        if (glyph != null && glyph.texture() == null) return;
        nvgSave(vg);
        nvgTranslate(vg, x, y);
        // vanilla shears italics by a quarter pixel per row around row 4 of the line
        if (italic) nvgTransform(vg, 1f, 0f, -0.25f, 1f, scale, 0f);
        if (glyph == null) {
            fallback(vg, paint, codepoint, scale, argb);
        } else {
            McTextures.Texture texture = McTextures.get(glyph.texture());
            if (!texture.missing()) {
                float sx = glyph.pixelScale() * scale;
                float top = glyph.top() * scale;
                nvgImagePattern(vg, -glyph.u() * sx, top - glyph.v() * sx, texture.width() * sx,
                        texture.height() * sx, 0f, texture.handle(), 1f, paint);
                NVGRenderer.color(argb, paint.innerColor());
                NVGRenderer.color(argb, paint.outerColor());
                nvgBeginPath(vg);
                nvgRect(vg, 0f, top, glyph.width() * sx, glyph.height() * sx);
                nvgFillPaint(vg, paint);
                nvgFill(vg);
            }
        }
        nvgRestore(vg);
    }

    private static void fallback(long vg, NVGPaint paint, int codepoint, int scale, int argb) {
        int font = NanoVGManager.getFontId(Fonts.UI_SEMIBOLD);
        if (font == -1) return;
        nvgFontFaceId(vg, font);
        nvgFontSize(vg, FALLBACK_SIZE * scale);
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_BASELINE);
        NVGRenderer.color(argb, paint.innerColor());
        nvgFillColor(vg, paint.innerColor());
        nvgText(vg, 0f, BASELINE * scale, Character.toString(codepoint));
    }

    private static Glyph obfuscated(Table table, Glyph glyph) {
        Glyph[] candidates = table.byAdvance().get(glyph.advance());
        return candidates == null ? glyph : candidates[ThreadLocalRandom.current().nextInt(candidates.length)];
    }

    // § colours replace the rgb and keep the caller's alpha; shadows are the colour at a quarter brightness
    private static int colour(int rgb, int color, boolean shadow) {
        int argb = rgb < 0 ? color : (color & 0xFF000000) | rgb;
        if (!shadow) return argb;
        return (argb & 0xFF000000) | (((argb >> 16) & 0xFF) / 4) << 16 | (((argb >> 8) & 0xFF) / 4) << 8 | (argb & 0xFF) / 4;
    }

    private static boolean snapsToDevicePixels(long vg) {
        nvgCurrentTransform(vg, transform);
        float scale = transform[0];
        return scale > 0f && Math.abs(transform[1]) <= 1e-5f && Math.abs(transform[2]) <= 1e-5f
                && Math.abs(transform[3] - scale) <= scale * 1e-4f;
    }

    private static float onDevicePixel(float local, float scale, float offset, float ratio) {
        return (Math.round((local * scale + offset) * ratio) / ratio - offset) / scale;
    }

    // -- layout ---------------------------------------------------------------

    private static Layout layout(String text, boolean formatted, int scale) {
        if (text == null) text = "";
        LayoutKey key = new LayoutKey(text, formatted, Math.max(1, scale));
        synchronized (layouts) {
            Layout cached = layouts.get(key);
            if (cached != null) return cached;
        }
        Layout layout = build(key);
        synchronized (layouts) {
            layouts.put(key, layout);
        }
        return layout;
    }

    private static Layout build(LayoutKey key) {
        String text = key.text();
        int scale = key.scale();
        Int2ObjectMap<Glyph> glyphs = table().glyphs();
        List<Glyph> placed = new ArrayList<>();
        List<Integer> codepoints = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        List<Integer> styles = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        int strike = -1;
        int underline = -1;

        float x = 0f;
        int colour = -1;
        int style = 0;
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (key.formatted() && c == SECTION) {
                if (i + 1 >= text.length()) break;
                int code = CODES.indexOf(Character.toLowerCase(text.charAt(i + 1)));
                if (code >= 0 && code < 16) {
                    colour = COLOURS[code];
                    style = 0;
                } else if (code >= 16) {
                    int format = code - 16;
                    if (format == 5) {
                        colour = -1;
                        style = 0;
                    } else {
                        style |= 1 << format;
                    }
                }
                i += 2;
                continue;
            }
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint < 0x20) continue;
            Glyph glyph = glyphs.get(codepoint);
            float advance = glyph != null ? glyph.advance() * scale : fallbackAdvance(codepoint, scale);
            if ((style & BOLD) != 0) advance += scale;

            placed.add(glyph);
            codepoints.add(codepoint);
            xs.add(x);
            colours.add(colour);
            styles.add(style);
            // vanilla starts the first character's lines a pixel early
            float lineStart = placed.size() == 1 ? x - scale : x;
            strike = (style & STRIKETHROUGH) != 0 ? extend(lines, strike, lineStart, x + advance, 3.5f, colour) : -1;
            underline = (style & UNDERLINE) != 0 ? extend(lines, underline, lineStart, x + advance, 8f, colour) : -1;
            x += advance;
        }

        float[] positions = new float[xs.size()];
        int[] colourArray = new int[xs.size()];
        int[] styleArray = new int[xs.size()];
        int[] codepointArray = new int[xs.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = xs.get(i);
            colourArray[i] = colours.get(i);
            styleArray[i] = styles.get(i);
            codepointArray[i] = codepoints.get(i);
        }
        return new Layout(placed.toArray(Glyph[]::new), codepointArray, positions, colourArray, styleArray,
                List.copyOf(lines), x);
    }

    // one rect per same-coloured run, so translucent text does not double up where per-glyph lines would overlap
    private static int extend(List<Line> lines, int open, float x0, float x1, float y, int colour) {
        if (open >= 0 && lines.get(open).colour() == colour) {
            lines.set(open, new Line(lines.get(open).x0(), x1, y, colour));
            return open;
        }
        lines.add(new Line(x0, x1, y, colour));
        return lines.size() - 1;
    }

    private static float fallbackAdvance(int codepoint, int scale) {
        int font = NanoVGManager.isInitialized() ? NanoVGManager.getFontId(Fonts.UI_SEMIBOLD) : -1;
        if (font == -1) return FALLBACK_ESTIMATE * scale;
        long vg = NanoVGManager.getVg();
        nvgSave(vg);
        nvgFontFaceId(vg, font);
        nvgFontSize(vg, FALLBACK_SIZE * scale);
        float advance = nvgTextBounds(vg, 0f, 0f, Character.toString(codepoint), bounds);
        nvgRestore(vg);
        return advance;
    }

    private static List<String> wrap(String text, float maxWidth, int scale, boolean formatted) {
        List<String> out = new ArrayList<>();
        if (text == null) text = "";
        String carry = "";
        for (String paragraph : text.split("\n", -1)) {
            String line = carry + paragraph;
            while (true) {
                int cut = fit(line, maxWidth, scale, formatted, true);
                if (cut >= line.length()) break;
                String head = line.substring(0, cut);
                String rest = line.substring(cut).stripLeading();
                out.add(head.stripTrailing());
                line = (formatted ? activeCodes(head) : "") + rest;
            }
            out.add(line);
            carry = formatted ? activeCodes(line) : "";
        }
        return out;
    }

    private static String ellipsize(String text, float maxWidth, int scale, boolean formatted) {
        if (text == null) return "";
        if (layout(text, formatted, scale).width() <= maxWidth) return text;
        float room = maxWidth - layout(ELLIPSIS, false, scale).width();
        return text.substring(0, fit(text, room, scale, formatted, false)).stripTrailing() + ELLIPSIS;
    }

    // characters of text that fit in maxWidth, preferring to end after a space when breakAtSpace; always at least
    // one visible character when breaking lines, so wrapping cannot stall
    private static int fit(String text, float maxWidth, int scale, boolean formatted, boolean breakAtSpace) {
        Int2ObjectMap<Glyph> glyphs = table().glyphs();
        float x = 0f;
        boolean bold = false;
        int lastSpace = -1;
        boolean visible = false;
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (formatted && c == SECTION) {
                if (i + 1 >= text.length()) return text.length();
                int code = CODES.indexOf(Character.toLowerCase(text.charAt(i + 1)));
                if (code >= 0 && code < 16 || code == 21) bold = false;
                else if (code == 17) bold = true;
                i += 2;
                continue;
            }
            int codepoint = text.codePointAt(i);
            Glyph glyph = glyphs.get(codepoint);
            float advance = codepoint < 0x20 ? 0f : glyph != null ? glyph.advance() * scale : fallbackAdvance(codepoint, scale);
            if (bold) advance += scale;
            if (x + advance > maxWidth && (visible || !breakAtSpace)) {
                if (!breakAtSpace || codepoint == ' ') return i;
                return lastSpace > 0 ? lastSpace : i;
            }
            x += advance;
            visible = true;
            i += Character.charCount(codepoint);
            if (codepoint == ' ') lastSpace = i;
        }
        return text.length();
    }

    // the § codes that reproduce the colour and styles in effect at the end of text
    private static String activeCodes(String text) {
        int colour = -1;
        StringBuilder styles = new StringBuilder();
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != SECTION) continue;
            char code = Character.toLowerCase(text.charAt(i + 1));
            int index = CODES.indexOf(code);
            if (index >= 0 && index < 16) {
                colour = index;
                styles.setLength(0);
            } else if (code == 'r') {
                colour = -1;
                styles.setLength(0);
            } else if (index >= 16 && styles.indexOf(String.valueOf(code)) < 0) {
                styles.append(SECTION).append(code);
            }
            i++;
        }
        return (colour < 0 ? "" : SECTION + String.valueOf(CODES.charAt(colour))) + styles;
    }

    // -- glyph table ----------------------------------------------------------

    static Table table() {
        Table current = table;
        if (current != null) return current;
        synchronized (McBitmapFont.class) {
            if (table == null) table = load();
            return table;
        }
    }

    private static Table load() {
        Int2ObjectOpenHashMap<Glyph> glyphs = new Int2ObjectOpenHashMap<>();
        collect(Identifier.withDefaultNamespace("default"), glyphs, new HashSet<>());
        if (glyphs.isEmpty()) System.err.println("[Aether] Minecraft's default font has no bitmap glyphs");
        Map<Float, List<Glyph>> buckets = new LinkedHashMap<>();
        for (Glyph glyph : glyphs.values()) {
            if (glyph.texture() != null) buckets.computeIfAbsent(glyph.advance(), a -> new ArrayList<>()).add(glyph);
        }
        Float2ObjectOpenHashMap<Glyph[]> byAdvance = new Float2ObjectOpenHashMap<>();
        buckets.forEach((advance, list) -> byAdvance.put((float) advance, list.toArray(Glyph[]::new)));
        return new Table(glyphs, byAdvance);
    }

    // vanilla merges a font's definitions across packs, higher packs first, and the first provider with a code
    // point wins; ttf and unihex providers are left to the inter fallback
    private static void collect(Identifier font, Int2ObjectMap<Glyph> glyphs, Set<Identifier> visiting) {
        if (!visiting.add(font)) return;
        Identifier location = font.withPath(path -> "font/" + path + ".json");
        for (IoSupplier<InputStream> copy : McAssets.openAll(location)) {
            JsonObject definition = readJson(copy, location);
            if (definition == null || !definition.has("providers")) continue;
            for (JsonElement element : definition.getAsJsonArray("providers")) {
                if (!element.isJsonObject()) continue;
                JsonObject provider = element.getAsJsonObject();
                if (!activeByDefault(provider)) continue;
                switch (path(string(provider, "type"))) {
                    case "reference" -> {
                        Identifier id = Identifier.tryParse(string(provider, "id"));
                        if (id != null) collect(id, glyphs, visiting);
                    }
                    case "space" -> space(provider, glyphs);
                    case "bitmap" -> bitmap(provider, glyphs);
                    default -> {}
                }
            }
        }
        visiting.remove(font);
    }

    // filters select providers by the force-unicode and japanese-variant options, both off by default
    private static boolean activeByDefault(JsonObject provider) {
        JsonElement filter = provider.get("filter");
        if (filter == null || !filter.isJsonObject()) return true;
        for (Map.Entry<String, JsonElement> option : filter.getAsJsonObject().entrySet()) {
            if (option.getValue().getAsBoolean()) return false;
        }
        return true;
    }

    private static void space(JsonObject provider, Int2ObjectMap<Glyph> glyphs) {
        JsonElement advances = provider.get("advances");
        if (advances == null || !advances.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : advances.getAsJsonObject().entrySet()) {
            int[] codepoints = entry.getKey().codePoints().toArray();
            if (codepoints.length != 1) continue;
            glyphs.putIfAbsent(codepoints[0], new Glyph(null, 0, 0, 0, 0, 1f, entry.getValue().getAsFloat(), 0f));
        }
    }

    private static void bitmap(JsonObject provider, Int2ObjectMap<Glyph> glyphs) {
        Identifier file = Identifier.tryParse(string(provider, "file"));
        if (file == null || !provider.has("chars") || !provider.has("ascent")) return;
        String texture = file.getNamespace() + ":textures/" + file.getPath();
        List<int[]> rows = new ArrayList<>();
        for (JsonElement row : provider.getAsJsonArray("chars")) rows.add(row.getAsString().codePoints().toArray());
        if (rows.isEmpty() || rows.getFirst().length == 0) return;
        McTextures.Pixels pixels = McTextures.decode(texture);
        if (pixels == null) return;
        try {
            int cellW = pixels.width() / rows.getFirst().length;
            int cellH = pixels.height() / rows.size();
            if (cellW <= 0 || cellH <= 0) return;
            int height = provider.has("height") ? provider.get("height").getAsInt() : 8;
            int ascent = provider.get("ascent").getAsInt();
            float pixelScale = (float) height / cellH;
            for (int row = 0; row < rows.size(); row++) {
                int[] codepoints = rows.get(row);
                for (int col = 0; col < codepoints.length; col++) {
                    if (codepoints[col] == 0 || (col + 1) * cellW > pixels.width()) continue;
                    int inked = inkedWidth(pixels, col * cellW, row * cellH, cellW, cellH);
                    float advance = (int) (0.5 + inked * pixelScale) + 1;
                    glyphs.putIfAbsent(codepoints[col], new Glyph(texture, col * cellW, row * cellH, cellW, cellH,
                            pixelScale, advance, BASELINE - ascent));
                }
            }
        } finally {
            MemoryUtil.memFree(pixels.rgba());
        }
    }

    // vanilla's advance source: one past the rightmost column with any non-transparent pixel
    private static int inkedWidth(McTextures.Pixels pixels, int left, int top, int width, int height) {
        for (int column = width - 1; column >= 0; column--) {
            for (int row = 0; row < height; row++) {
                int index = ((top + row) * pixels.width() + left + column) * 4 + 3;
                if (pixels.rgba().get(index) != 0) return column + 1;
            }
        }
        return 0;
    }

    private static JsonObject readJson(IoSupplier<InputStream> copy, Identifier location) {
        try (InputStream in = copy.get()) {
            if (in == null) return null;
            JsonElement json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Aether] Could not read font definition " + location + ": " + e.getMessage());
            return null;
        }
    }

    private static String string(JsonObject node, String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String path(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
