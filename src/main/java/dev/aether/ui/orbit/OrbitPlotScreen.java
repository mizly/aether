package dev.aether.ui.orbit;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.plot.GardenFacts;
import dev.aether.ui.gui.plot.GardenPlotData;
import dev.aether.ui.gui.plot.PlotPickerModel;
import dev.aether.ui.settings.PlotSetting;
import dev.aether.ui.settings.PlotToken;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

import java.util.List;

// the big rotatable garden for a plot setting; it grows out of the row's thumbnail and shrinks back into it
final class OrbitPlotScreen {
    static final float THUMB_PITCH = 42f;
    private static final float PITCH = 38f;

    private final PlotSetting setting;
    private final PlotPickerModel model;
    private final float[] from;
    private final float fromYaw;
    private final OrbitSpring open = new OrbitSpring(0f, 70f, 13f);
    private final OrbitSpring yaw;
    private final OrbitSpring pitch = new OrbitSpring(THUMB_PITCH, 40f, 12f);
    private final OrbitSpring[] lift = new OrbitSpring[PlotToken.MAX_PLOT + 1];
    private boolean closing;
    private boolean dragging;
    private boolean dragged;
    private double dragX;
    private double dragY;
    private int hover = -1;
    private List<PlotDiorama.Pick> picks = List.of();
    private float[] clear = new float[4];
    private float[] done = new float[4];

    // from is the thumbnail's screen rect {x, y, w, h}; fromYaw the angle it was showing
    OrbitPlotScreen(PlotSetting setting, float[] from, float fromYaw) {
        this.setting = setting;
        this.model = new PlotPickerModel(setting);
        this.from = from;
        this.fromYaw = fromYaw;
        this.yaw = new OrbitSpring(fromYaw, 40f, 12f);
        yaw.t = 32f;
        pitch.t = PITCH;
        open.t = 1f;
        for (int i = 0; i < lift.length; i++) {
            lift[i] = new OrbitSpring(setting.isSelected(token(i)) ? 1f : 0f, 160f, 13f);
        }
    }

    // thumbnails turn slowly so the garden reads as a model, not a flat map
    static float thumbYaw(float time) {
        return 26f + (float) Math.sin(time * 0.45) * 22f;
    }

    static PlotDiorama.View thumbView(float x, float y, float w, float h, float yaw) {
        float scale = Math.min(w, h) / 5.9f;
        return new PlotDiorama.View(x + w / 2f, y + h / 2f + scale * 0.35f, scale, yaw, THUMB_PITCH);
    }

    boolean finished() {
        return closing && open.x < 0.02f;
    }

    void close() {
        if (closing) return;
        closing = true;
        open.t = 0f;
        yaw.t = fromYaw;
        pitch.t = THUMB_PITCH;
    }

    void render(NVGRenderer nvg, float w, float h, float mx, float my, float dt, float time) {
        open.step(dt);
        yaw.step(dt);
        pitch.step(dt);
        GardenFacts facts = GardenFacts.read(GardenPlotData.active());
        for (int i = 0; i < lift.length; i++) {
            lift[i].t = model.look(i, facts).marked() ? 1f : 0f;
            lift[i].step(dt);
        }
        float t = OrbitRig.clamp(open.x, 0f, 1.2f);
        float k = OrbitRig.clamp(t, 0f, 1f);
        Palette p = Palette.fromTheme();
        nvg.rect(0, 0, w, h, Argb.withAlpha(0xFF05070A, 0.66f * k));

        float fullScale = Math.min(w, h * 1.25f) * 0.105f;
        PlotDiorama.View start = thumbView(from[0], from[1], from[2], from[3], fromYaw);
        float cx = lerp(start.cx(), w / 2f, t);
        float cy = lerp(start.cy(), h * 0.44f, t);
        float scale = lerp(start.scale(), fullScale, t);
        PlotDiorama.View view = new PlotDiorama.View(cx, cy, scale, yaw.x, pitch.x);
        float[] lifts = new float[lift.length];
        for (int i = 0; i < lift.length; i++) lifts[i] = lift[i].x;
        hover = closing ? -1 : pickAt(mx, my);
        picks = PlotDiorama.draw(nvg, view, plot -> model.look(plot, facts), lifts, hover, time, t > 0.55f,
                p.accent(), 1f);

        if (k < 0.6f) return;
        float a = (k - 0.6f) / 0.4f;
        drawCard(nvg, p, w, h, a, facts, mx, my);
        if (hover >= 0) drawTooltip(nvg, p, mx, my, model.tooltip(hover, facts), a);
    }

    private void drawCard(NVGRenderer nvg, Palette p, float w, float h, float a, GardenFacts facts, float mx, float my) {
        float cw = Math.min(380f, w - 40f);
        float ch = 58f;
        float x = (w - cw) / 2f;
        float y = h - ch - 46f;
        nvg.roundedRect(x, y, cw, ch, 10f, Argb.multiplyAlpha(Argb.withAlpha(p.panel(), 0.94f), a));
        nvg.rectOutline(x, y, cw, ch, 10f, 1f, Argb.multiplyAlpha(Argb.withAlpha(p.border(), 0.5f), a));
        nvg.text(Fonts.UI_SEMIBOLD, setting.getName(), x + 14f, y + 11f, 10f, Argb.multiplyAlpha(p.text(), a));
        String summary = PlotPickerModel.summary(setting);
        nvg.text(Fonts.UI_REGULAR, summary, x + 14f, y + 27f, 8f, Argb.multiplyAlpha(p.textMuted(), a));
        String hint = setting.mode() == PlotSetting.Mode.SINGLE
                ? AetherLang.localize("Click a plot to pick it · drag to turn the garden")
                : AetherLang.localize("Click plots to toggle them · drag to turn the garden");
        nvg.text(Fonts.UI_REGULAR, hint, x + 14f, y + 40f, 7f, Argb.multiplyAlpha(Argb.withAlpha(p.text(), 0.5f), a));

        float bw = 52f;
        float bh = 22f;
        done = new float[]{x + cw - 12f - bw, y + (ch - bh) / 2f, bw, bh};
        clear = new float[]{done[0] - 8f - bw, done[1], bw, bh};
        boolean doneHover = inside(done, mx, my);
        boolean clearHover = inside(clear, mx, my);
        nvg.roundedRect(clear[0], clear[1], bw, bh, 7f,
                Argb.multiplyAlpha(Argb.withAlpha(p.text(), clearHover ? 0.14f : 0.07f), a));
        centered(nvg, Fonts.UI_MEDIUM, AetherLang.localize("Clear"), clear, 8.5f, Argb.multiplyAlpha(p.text(), a));
        nvg.roundedRect(done[0], done[1], bw, bh, 7f,
                Argb.multiplyAlpha(doneHover ? Argb.mix(p.accent(), 0xFFFFFFFF, 0.12f) : p.accent(), a));
        centered(nvg, Fonts.UI_SEMIBOLD, AetherLang.localize("Done"), done, 8.5f, Argb.multiplyAlpha(p.onAccent(), a));
    }

    private static void drawTooltip(NVGRenderer nvg, Palette p, float mx, float my, List<String> lines, float a) {
        if (lines == null || lines.isEmpty()) return;
        float fs = 8f;
        float tw = 0f;
        for (String line : lines) tw = Math.max(tw, nvg.textWidth(Fonts.UI_REGULAR, strip(line), fs));
        float th = lines.size() * 11f + 8f;
        float x = mx + 12f;
        float y = my + 10f;
        nvg.roundedRect(x, y, tw + 14f, th, 6f, Argb.multiplyAlpha(0xF0100010, a));
        nvg.rectOutline(x, y, tw + 14f, th, 6f, 1f, Argb.multiplyAlpha(0xFF3A1A6E, a));
        float ly = y + 5f;
        boolean first = true;
        for (String line : lines) {
            nvg.text(first ? Fonts.UI_SEMIBOLD : Fonts.UI_REGULAR, strip(line), x + 7f, ly, fs,
                    Argb.multiplyAlpha(first ? 0xFF55FF55 : 0xFFAAAAAA, a));
            ly += 11f;
            first = false;
        }
    }

    private static String strip(String formatted) {
        return formatted == null ? "" : formatted.replaceAll("§.", "");
    }

    boolean click(double x, double y, int button) {
        if (closing) return true;
        if (inside(done, x, y)) {
            close();
            return true;
        }
        if (inside(clear, x, y)) {
            setting.clear();
            return true;
        }
        dragging = true;
        dragged = false;
        dragX = x;
        dragY = y;
        return true;
    }

    void drag(double x, double y) {
        if (!dragging) return;
        double dx = x - dragX, dy = y - dragY;
        if (Math.abs(dx) + Math.abs(dy) > 2) dragged = true;
        if (!dragged) return;
        yaw.t += (float) dx * 0.6f;
        yaw.x = yaw.t;
        pitch.t = OrbitRig.clamp(pitch.t + (float) dy * 0.3f, 18f, 72f);
        pitch.x = pitch.t;
        dragX = x;
        dragY = y;
    }

    void release(double x, double y) {
        if (dragging && !dragged) {
            int plot = pickAt((float) x, (float) y);
            if (plot > 0 || plot == PlotToken.BARN && setting.allowsBarn()) {
                model.click(plot, GardenFacts.read(GardenPlotData.active()));
            }
        }
        dragging = false;
    }

    private int pickAt(float x, float y) {
        for (int i = picks.size() - 1; i >= 0; i--) {
            if (picks.get(i).contains(x, y)) return picks.get(i).plot();
        }
        return -1;
    }

    private static PlotToken token(int plot) {
        return plot == PlotToken.BARN ? PlotToken.barn() : PlotToken.plot(plot);
    }

    private static boolean inside(float[] r, double x, double y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    private static void centered(NVGRenderer nvg, String font, String text, float[] r, float size, int color) {
        float tw = nvg.textWidth(font, text, size);
        nvg.text(font, text, r[0] + (r[2] - tw) / 2f, r[1] + (r[3] - size) / 2f, size, color);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
