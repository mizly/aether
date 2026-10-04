package dev.aether.ui.gui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NanoVG;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// the only way ui.gui draws: one state stack whose transform (translate and positive scale), root-space clip
// and multiplied alpha are mirrored in java for hit testing, literal text with cached measurement, and icons.
// a frame begun without a renderer keeps the mirror and measures but draws nothing
public final class GuiCanvas {
    // nanovg caps its state stack at 32, and past that save() silently does nothing while restore() pops a real state
    public static final int MAX_DEPTH = 24;
    private static final int TEXT_CACHE_SIZE = 4096;
    private static final String ELLIPSIS = "…";

    private static volatile ItemPainter itemPainter = GuiCanvas::placeholderItem;

    private final TextMetrics headlessMetrics;
    private final State[] stack = new State[MAX_DEPTH];
    private final Map<TextKey, Float> widths = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TextKey, Float> eldest) {
            return size() > TEXT_CACHE_SIZE;
        }
    };
    private final Map<FontKey, Float> lineHeights = new HashMap<>();
    private final float[] transform = new float[6];
    private NVGRenderer nvg;
    private long vg;
    private TextMetrics metrics;
    private NanoVgTextMetrics nanoMetrics;
    private NVGColor pathColor;
    private float deviceScale = 1f;
    private float rasterRatio = 1f;
    private Rect viewport = Rect.EMPTY;
    private State state = State.root(Rect.EMPTY);
    private int depth;

    public GuiCanvas() {
        this(null);
    }

    // headlessMetrics measures frames begun without a renderer (unit tests); null forbids them
    public GuiCanvas(TextMetrics headlessMetrics) {
        this.headlessMetrics = headlessMetrics;
    }

    public static void installItemPainter(ItemPainter painter) {
        itemPainter = painter;
    }

    public void begin(NVGRenderer renderer, float width, float height) {
        TextMetrics next;
        if (renderer == null) {
            if (headlessMetrics == null) {
                throw new IllegalStateException("this canvas has no metrics for frames without a renderer");
            }
            next = headlessMetrics;
            vg = 0L;
            deviceScale = 1f;
            rasterRatio = 1f;
        } else {
            vg = NanoVGManager.getVg();
            if (nanoMetrics == null) {
                nanoMetrics = new NanoVgTextMetrics(vg);
            }
            next = nanoMetrics;
            NanoVG.nvgCurrentTransform(vg, transform);
            rasterRatio = NanoVGManager.getPxRatio();
            deviceScale = (float) Math.hypot(transform[0], transform[1]) * rasterRatio;
        }
        if (next != metrics) {
            widths.clear();
            lineHeights.clear();
            metrics = next;
        }
        nvg = renderer;
        depth = 0;
        viewport = new Rect(0f, 0f, width, height);
        state = State.root(viewport);
    }

    public void end() {
        int open = depth;
        nvg = null;
        vg = 0L;
        depth = 0;
        state = State.root(viewport);
        if (open != 0) {
            throw new IllegalStateException("canvas frame ended with " + open + " unrestored saves");
        }
    }

    public Rect bounds() {
        return viewport;
    }

    // -- state -----------------------------------------------------------------

    public void save() {
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException("canvas state is deeper than " + MAX_DEPTH);
        }
        stack[depth++] = state;
        if (nvg != null) {
            nvg.save();
        }
    }

    public void restore() {
        if (depth == 0) {
            throw new IllegalStateException("canvas restore without a matching save");
        }
        state = stack[--depth];
        stack[depth] = null;
        if (nvg != null) {
            nvg.restore();
        }
    }

    public int depth() {
        return depth;
    }

    public void translate(float dx, float dy) {
        state = new State(state.tx + dx * state.sx, state.ty + dy * state.sy, state.sx, state.sy, state.clip, state.alpha);
        if (nvg != null) {
            nvg.translate(dx, dy);
        }
    }

    public void scale(float factor) {
        scale(factor, factor);
    }

    // hit regions are mapped through the mirror, which only models translate and positive scale
    public void scale(float sx, float sy) {
        if (!(sx > 0f) || !(sy > 0f)) {
            throw new IllegalArgumentException("canvas scale must be positive, got " + sx + " x " + sy);
        }
        state = new State(state.tx, state.ty, state.sx * sx, state.sy * sy, state.clip, state.alpha);
        if (nvg != null) {
            nvg.scale(sx, sy);
        }
    }

    public void clip(Rect local) {
        state = new State(state.tx, state.ty, state.sx, state.sy, state.clip.intersect(toRoot(local)), state.alpha);
        if (nvg != null) {
            NanoVG.nvgIntersectScissor(vg, local.x(), local.y(), Math.max(0f, local.w()), Math.max(0f, local.h()));
        }
    }

    // multiplies, so a fading panel inside a fading page composes; folded into every colour drawn
    public void alpha(float factor) {
        float clamped = Math.max(0f, Math.min(1f, factor));
        state = new State(state.tx, state.ty, state.sx, state.sy, state.clip, state.alpha * clamped);
    }

    public float alpha() {
        return state.alpha;
    }

    public float scaleX() {
        return state.sx;
    }

    public float scaleY() {
        return state.sy;
    }

    public Rect rootClip() {
        return state.clip;
    }

    public Rect toRoot(Rect local) {
        return new Rect(state.tx + local.x() * state.sx, state.ty + local.y() * state.sy,
                local.w() * state.sx, local.h() * state.sy);
    }

    public float toLocalX(float rootX) {
        return (rootX - state.tx) / state.sx;
    }

    public float toLocalY(float rootY) {
        return (rootY - state.ty) / state.sy;
    }

    public boolean isVisible(Rect local) {
        return !state.clip.intersect(toRoot(local)).isEmpty();
    }

    // -- shapes ----------------------------------------------------------------

    public void rect(Rect r, int argb) {
        int color = fold(argb);
        if (visible(color)) {
            nvg.rect(r.x(), r.y(), r.w(), r.h(), color);
        }
    }

    public void roundedRect(Rect r, float radius, int argb) {
        int color = fold(argb);
        if (visible(color)) {
            nvg.roundedRect(r.x(), r.y(), r.w(), r.h(), radius, color);
        }
    }

    public void strokeRect(Rect r, float radius, float width, int argb) {
        int color = fold(argb);
        if (visible(color)) {
            nvg.rectOutline(r.x(), r.y(), r.w(), r.h(), radius, width, color);
        }
    }

    public void circle(float cx, float cy, float radius, int argb) {
        int color = fold(argb);
        if (visible(color)) {
            nvg.circle(cx, cy, radius, color);
        }
    }

    public void strokeCircle(float cx, float cy, float radius, float width, int argb) {
        int color = fold(argb);
        if (visible(color)) {
            nvg.circleOutline(cx, cy, radius, width, color);
        }
    }

    public void line(float x1, float y1, float x2, float y2, float width, int argb) {
        int color = fold(argb);
        if (visible(color)) {
            nvg.line(x1, y1, x2, y2, width, color);
        }
    }

    public void verticalGradient(Rect r, float radius, int top, int bottom) {
        if (nvg != null) {
            nvg.linearGradient(r.x(), r.y(), r.w(), r.h(), radius, fold(top), fold(bottom));
        }
    }

    public void horizontalGradient(Rect r, float radius, int left, int right) {
        if (nvg != null) {
            nvg.horizontalGradient(r.x(), r.y(), r.w(), r.h(), radius, fold(left), fold(right));
        }
    }

    // draw before the element it belongs to
    public void shadow(Rect r, float radius, float blur, int argb) {
        int color = fold(argb);
        if (visible(color)) {
            nvg.shadow(r.x(), r.y(), r.w(), r.h(), radius, blur, color);
        }
    }

    // glyph-like marks (chevrons, checks, dots) are paths, because the ui fonts lack most of those glyphs
    public void beginPath() {
        if (nvg != null) {
            NanoVG.nvgBeginPath(vg);
        }
    }

    public void moveTo(float x, float y) {
        if (nvg != null) {
            NanoVG.nvgMoveTo(vg, x, y);
        }
    }

    public void lineTo(float x, float y) {
        if (nvg != null) {
            NanoVG.nvgLineTo(vg, x, y);
        }
    }

    public void closePath() {
        if (nvg != null) {
            NanoVG.nvgClosePath(vg);
        }
    }

    public void fillPath(int argb) {
        int color = fold(argb);
        if (visible(color)) {
            NanoVG.nvgFillColor(vg, pathColor(color));
            NanoVG.nvgFill(vg);
        }
    }

    // round caps and joins; the state change stays inside a save so other strokes keep their caps
    public void strokePath(float width, int argb) {
        int color = fold(argb);
        if (visible(color)) {
            NanoVG.nvgSave(vg);
            NanoVG.nvgLineCap(vg, NanoVG.NVG_ROUND);
            NanoVG.nvgLineJoin(vg, NanoVG.NVG_ROUND);
            NanoVG.nvgStrokeWidth(vg, width);
            NanoVG.nvgStrokeColor(vg, pathColor(color));
            NanoVG.nvgStroke(vg);
            NanoVG.nvgRestore(vg);
        }
    }

    // -- text ------------------------------------------------------------------

    // literal: no localisation and no global text scale, y is the top of the line
    public void text(String font, float size, String text, float x, float y, int argb) {
        int color = fold(argb);
        if (!text.isEmpty() && visible(color)) {
            nvg.textLiteral(font, text, x, y, size, color);
        }
    }

    public float textWidth(String font, float size, String text) {
        if (text.isEmpty()) {
            return 0f;
        }
        TextKey key = new TextKey(font, size, text);
        Float cached = widths.get(key);
        if (cached != null) {
            return cached;
        }
        float width = metrics.width(font, size, text);
        widths.put(key, width);
        return width;
    }

    public float lineHeight(String font, float size) {
        return lineHeights.computeIfAbsent(new FontKey(font, size), key -> metrics.lineHeight(font, size));
    }

    // this frame's measuring backend, which the focused field hands to TextEditor.layout
    public TextMetrics metrics() {
        return metrics;
    }

    // keeps explicit line breaks and splits words wider than a line; never returns an empty list
    public List<String> wrap(String font, float size, String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            String line = "";
            for (String word : paragraph.trim().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (textWidth(font, size, candidate) <= maxWidth) {
                    line = candidate;
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line);
                }
                line = word;
                while (line.length() > 1 && textWidth(font, size, line) > maxWidth) {
                    int cut = Math.max(1, fittingPrefix(font, size, line, maxWidth));
                    lines.add(line.substring(0, cut));
                    line = line.substring(cut);
                }
            }
            lines.add(line);
        }
        return lines;
    }

    public String ellipsize(String font, float size, String text, float maxWidth) {
        if (textWidth(font, size, text) <= maxWidth) {
            return text;
        }
        float ellipsisWidth = textWidth(font, size, ELLIPSIS);
        if (ellipsisWidth > maxWidth) {
            return "";
        }
        int cut = fittingPrefix(font, size, text, maxWidth - ellipsisWidth);
        return text.substring(0, cut).stripTrailing() + ELLIPSIS;
    }

    // -- icons -----------------------------------------------------------------

    public void icon(Icon icon, float x, float y, float size, int tint) {
        int color = fold(tint);
        if (size <= 0f || !visible(color)) {
            return;
        }
        switch (icon) {
            case Icon.Svg svg -> svg(svg.resourcePath(), x, y, size, color);
            case Icon.Item item -> itemPainter.paint(nvg, item.id(), x, y, size, color);
        }
    }

    // rasterises at the device size rounded up to a 2 px bucket, so scale changes reuse a few rasters
    private void svg(String path, float x, float y, float size, int tint) {
        float device = size * state.sx * deviceScale;
        float bucket = Math.max(2f, (float) Math.ceil(device / 2f) * 2f);
        float drawn = bucket / rasterRatio;
        float s = size / drawn;
        nvg.save();
        nvg.translate(x, y);
        nvg.scale(s, s);
        nvg.renderSVG(path, 0f, 0f, drawn, drawn, tint);
        nvg.restore();
    }

    // stands in until the renderer's minecraft item icons are installed
    private static void placeholderItem(NVGRenderer nvg, String itemId, float x, float y, float size, int tint) {
        float inset = size * 0.15f;
        nvg.roundedRect(x + inset, y + inset, size - inset * 2f, size - inset * 2f, size * 0.15f,
                Argb.multiplyAlpha(tint, 0.35f));
    }

    // -- legacy ----------------------------------------------------------------

    // old NVGRenderer helpers set absolute globalAlpha and reset scissors, so they run in their own save
    public void legacy(Consumer<NVGRenderer> draw) {
        if (nvg == null) {
            return;
        }
        save();
        NanoVG.nvgGlobalAlpha(vg, state.alpha);
        try {
            draw.accept(nvg);
        } finally {
            restore();
        }
    }

    // -- internals -------------------------------------------------------------

    private int fold(int argb) {
        return state.alpha >= 1f ? argb : Argb.multiplyAlpha(argb, state.alpha);
    }

    private boolean visible(int color) {
        return nvg != null && Argb.alpha(color) > 0;
    }

    private NVGColor pathColor(int argb) {
        if (pathColor == null) {
            pathColor = NVGColor.create();
        }
        return NanoVG.nvgRGBA((byte) (argb >> 16), (byte) (argb >> 8), (byte) argb, (byte) (argb >>> 24), pathColor);
    }

    // length of the longest prefix that fits, possibly 0, never splitting a surrogate pair
    private int fittingPrefix(String font, float size, String text, float maxWidth) {
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (textWidth(font, size, text.substring(0, mid)) <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low > 0 && low < text.length() && Character.isHighSurrogate(text.charAt(low - 1)) ? low - 1 : low;
    }

    private record State(float tx, float ty, float sx, float sy, Rect clip, float alpha) {
        static State root(Rect viewport) {
            return new State(0f, 0f, 1f, 1f, viewport, 1f);
        }
    }

    private record TextKey(String font, float size, String text) {
    }

    private record FontKey(String font, float size) {
    }
}
