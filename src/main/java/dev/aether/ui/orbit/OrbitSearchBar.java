package dev.aether.ui.orbit;

import dev.aether.renderer.McIcons;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.orbit.panel.PanelView;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

// the search field in the top bar: typing anywhere starts it, arrows pick a hit and enter flies the ring there
final class OrbitSearchBar {
    private static final float ROW_H = 25f;
    private static final int ROWS = 8;
    private static final int MAX_QUERY = 60;

    private final PanelView view;
    private final Runnable onRun;
    private final StringBuilder query = new StringBuilder();
    private boolean open;
    private String lastQuery;
    private List<PanelView.SearchHit> hits = List.of();
    private int selected;
    private int scroll;
    private float chipX, chipY, chipW, chipH;
    private float listX, listY, listW, listH;
    private int hoveredRow = -1;

    OrbitSearchBar(PanelView view, Runnable onRun) {
        this.view = view;
        this.onRun = onRun;
    }

    boolean isOpen() {
        return open;
    }

    void open(String initial) {
        open = true;
        query.setLength(0);
        if (initial != null) query.append(initial);
        lastQuery = null;
    }

    void close() {
        open = false;
        query.setLength(0);
        hits = List.of();
    }

    void type(String chars) {
        if (!open) open("");
        for (int i = 0; i < chars.length() && query.length() < MAX_QUERY; i++) query.append(chars.charAt(i));
    }

    boolean key(int key, boolean ctrl, boolean shift) {
        if (!open) return false;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (query.isEmpty()) close();
                else query.setLength(0);
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (ctrl) {
                    int end = query.length();
                    while (end > 0 && query.charAt(end - 1) == ' ') end--;
                    while (end > 0 && query.charAt(end - 1) != ' ') end--;
                    query.setLength(end);
                } else if (!query.isEmpty()) {
                    query.setLength(query.length() - 1);
                }
            }
            case GLFW.GLFW_KEY_DOWN -> move(1);
            case GLFW.GLFW_KEY_UP -> move(-1);
            case GLFW.GLFW_KEY_TAB -> move(shift ? -1 : 1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (selected >= 0 && selected < hits.size()) run(hits.get(selected));
            }
            default -> {
            }
        }
        return true;
    }

    private void move(int d) {
        if (hits.isEmpty()) return;
        selected = Math.floorMod(selected + d, hits.size());
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + ROWS) scroll = selected - ROWS + 1;
    }

    void scroll(double dy) {
        scroll = Math.max(0, Math.min(Math.max(0, hits.size() - ROWS), scroll + (dy < 0 ? 1 : -1)));
    }

    private void run(PanelView.SearchHit hit) {
        close();
        hit.run().run();
        onRun.run();
    }

    // returns true when the click belonged to the search: the field, a hit, or anywhere while the list is open
    boolean click(double x, double y) {
        if (inside(x, y, chipX, chipY, chipW, chipH)) {
            if (!open) open("");
            return true;
        }
        if (!open) return false;
        if (inside(x, y, listX, listY, listW, listH)) {
            int row = (int) ((y - listY - 4f) / ROW_H) + scroll;
            if (row >= 0 && row < hits.size() && y - listY - 4f >= 0) run(hits.get(row));
            return true;
        }
        close();
        return false;
    }

    boolean hovering(double x, double y) {
        return inside(x, y, chipX, chipY, chipW, chipH) || open && inside(x, y, listX, listY, listW, listH);
    }

    boolean over(double x, double y) {
        return open && inside(x, y, listX, listY, listW, listH);
    }

    private static boolean inside(double x, double y, float rx, float ry, float rw, float rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    // the field fills [x, x + w) of the top bar; a narrow bar shrinks it to the magnifier alone
    void render(NVGRenderer nvg, Palette p, float x, float y, float w, float screenW, float screenH, float mx, float my,
                float a) {
        refresh();
        chipX = x;
        chipY = y;
        chipW = w;
        chipH = 22f;
        int chip = Argb.multiplyAlpha(Argb.withAlpha(p.panel(), 0.88f), a);
        boolean hot = open || inside(mx, my, chipX, chipY, chipW, chipH);
        int border = Argb.multiplyAlpha(open ? Argb.withAlpha(p.accent(), 0.9f) : Argb.withAlpha(p.border(), hot ? 0.7f : 0.45f), a);
        nvg.roundedRect(x, y, w, chipH, 7f, chip);
        nvg.rectOutline(x, y, w, chipH, 7f, 1f, border);
        magnifier(nvg, x + 11f, y + 11f, Argb.multiplyAlpha(open ? p.accent() : p.textMuted(), a));
        if (w < 60f) {
            listW = listH = 0f;
            if (!open) return;
        } else {
            float tx = x + 21f;
            float maxText = w - 21f - 22f;
            nvg.pushScissor(tx, y, maxText, chipH);
            if (query.isEmpty()) {
                nvg.text(Fonts.UI_MEDIUM, open ? AetherLang.localize("Type to search") : AetherLang.localize("Search settings"),
                        tx, y + 7f, 8f, Argb.multiplyAlpha(p.textMuted(), a));
            } else {
                String q = query.toString();
                float qw = nvg.textWidth(Fonts.UI_MEDIUM, q, 8f);
                float shift = Math.max(0f, qw - maxText + 4f);
                nvg.text(Fonts.UI_MEDIUM, q, tx - shift, y + 7f, 8f, Argb.multiplyAlpha(p.text(), a));
                if (open && (System.nanoTime() / 530_000_000L) % 2L == 0L) {
                    nvg.rect(tx - shift + qw + 1f, y + 6f, 1f, 10f, Argb.multiplyAlpha(p.accent(), a));
                }
            }
            nvg.popScissor();
            keycap(nvg, p, x + w - 17f, y + 5f, open ? "Esc" : "/", a);
        }
        if (!open) {
            listW = listH = 0f;
            return;
        }
        drawList(nvg, p, screenW, screenH, mx, my, a);
    }

    private void drawList(NVGRenderer nvg, Palette p, float screenW, float screenH, float mx, float my, float a) {
        listW = Math.min(360f, screenW - 20f);
        listX = Math.max(10f, Math.min(screenW - 10f - listW, chipX + chipW / 2f - listW / 2f));
        listY = chipY + chipH + 5f;
        int rows = Math.min(ROWS, Math.max(1, hits.size()));
        listH = 4f + rows * ROW_H + 4f + 18f;
        listH = Math.min(listH, screenH - listY - 40f);
        nvg.shadow(listX, listY, listW, listH, 9f, 14f, Argb.multiplyAlpha(0x66000000, a));
        nvg.roundedRect(listX, listY, listW, listH, 9f, Argb.multiplyAlpha(Argb.withAlpha(p.panel(), 0.96f), a));
        nvg.rectOutline(listX, listY, listW, listH, 9f, 1f, Argb.multiplyAlpha(Argb.withAlpha(p.border(), 0.5f), a));
        hoveredRow = -1;
        float ry = listY + 4f;
        if (hits.isEmpty()) {
            String empty = AetherLang.localize("No results for") + " \"" + query + "\"";
            nvg.text(Fonts.UI_MEDIUM, empty, listX + 12f, ry + 8f, 8f, Argb.multiplyAlpha(p.textMuted(), a));
        }
        String needle = query.toString().trim().toLowerCase(Locale.ROOT);
        nvg.pushScissor(listX, listY + 2f, listW, listH - 22f);
        for (int i = scroll; i < Math.min(hits.size(), scroll + ROWS); i++) {
            PanelView.SearchHit hit = hits.get(i);
            float rowY = ry + (i - scroll) * ROW_H;
            boolean hover = inside(mx, my, listX, rowY, listW, ROW_H);
            if (hover) hoveredRow = i;
            if (i == selected) {
                nvg.roundedRect(listX + 4f, rowY, listW - 8f, ROW_H, 6f, Argb.multiplyAlpha(Argb.withAlpha(p.accent(), 0.18f), a));
            } else if (hover) {
                nvg.roundedRect(listX + 4f, rowY, listW - 8f, ROW_H, 6f, Argb.multiplyAlpha(Argb.withAlpha(p.text(), 0.06f), a));
            }
            icon(nvg, hit.icon(), listX + 10f, rowY + 6.5f, 12f, a, p);
            float tx = listX + 28f;
            float valueW = hit.value() == null ? 0f : nvg.textWidth(Fonts.UI_MEDIUM, hit.value(), 7f) + 10f;
            float textW = listW - 28f - 12f - valueW;
            highlighted(nvg, p, hit.title(), needle, tx, rowY + 4.5f, textW, a);
            if (hit.path() != null && !hit.path().isEmpty()) {
                nvg.pushScissor(tx, rowY, textW, ROW_H);
                nvg.text(Fonts.UI_REGULAR, hit.path(), tx, rowY + 14.5f, 6.5f, Argb.multiplyAlpha(p.textMuted(), a));
                nvg.popScissor();
            }
            String right = hit.value() != null ? hit.value() : AetherLang.localize(hit.kind());
            float rw = nvg.textWidth(Fonts.UI_MEDIUM, right, 7f);
            nvg.text(Fonts.UI_MEDIUM, right, listX + listW - 12f - rw, rowY + 9f, 7f,
                    Argb.multiplyAlpha(hit.value() != null ? p.textSecondary() : p.textMuted(), a));
        }
        nvg.popScissor();
        float fy = listY + listH - 16f;
        nvg.rect(listX + 8f, fy - 3f, listW - 16f, 1f, Argb.multiplyAlpha(Argb.withAlpha(p.border(), 0.3f), a));
        String hint = AetherLang.localize("↑↓ pick · Enter go there · Esc close");
        nvg.text(Fonts.UI_REGULAR, hint, listX + 12f, fy + 2f, 6.5f, Argb.multiplyAlpha(p.textMuted(), a));
        String count = hits.size() + " " + AetherLang.localize(hits.size() == 1 ? "result" : "results");
        float cw = nvg.textWidth(Fonts.UI_REGULAR, count, 6.5f);
        nvg.text(Fonts.UI_REGULAR, count, listX + listW - 12f - cw, fy + 2f, 6.5f, Argb.multiplyAlpha(p.textMuted(), a));
    }

    private void refresh() {
        String q = query.toString().trim();
        if (!open || q.equals(lastQuery)) return;
        lastQuery = q;
        hits = view.orbitSearch(q);
        selected = 0;
        scroll = 0;
    }

    // the title with the matched part of the query in the accent colour
    private static void highlighted(NVGRenderer nvg, Palette p, String title, String needle, float x, float y, float maxW,
                                    float a) {
        nvg.pushScissor(x, y - 2f, maxW, 12f);
        int at = needle.isEmpty() ? -1 : title.toLowerCase(Locale.ROOT).indexOf(needle);
        int text = Argb.multiplyAlpha(p.text(), a);
        if (at < 0) {
            nvg.text(Fonts.UI_SEMIBOLD, title, x, y, 8f, text);
        } else {
            String head = title.substring(0, at);
            String match = title.substring(at, at + needle.length());
            String tail = title.substring(at + needle.length());
            float hx = x;
            nvg.text(Fonts.UI_SEMIBOLD, head, hx, y, 8f, text);
            hx += nvg.textWidth(Fonts.UI_SEMIBOLD, head, 8f);
            nvg.text(Fonts.UI_SEMIBOLD, match, hx, y, 8f, Argb.multiplyAlpha(p.accent(), a));
            hx += nvg.textWidth(Fonts.UI_SEMIBOLD, match, 8f);
            nvg.text(Fonts.UI_SEMIBOLD, tail, hx, y, 8f, text);
        }
        nvg.popScissor();
    }

    private static void icon(NVGRenderer nvg, Icon icon, float x, float y, float size, float a, Palette p) {
        int white = Argb.multiplyAlpha(0xFFFFFFFF, a);
        switch (icon) {
            case null -> nvg.circle(x + size / 2f, y + size / 2f, 1.6f, Argb.multiplyAlpha(p.textMuted(), a));
            case Icon.Item item -> nvg.mcIcon(McIcons.of(item.id()), x, y, size, white);
            case Icon.Svg svg -> nvg.renderSVG(svg.resourcePath(), x, y, size, size, Argb.multiplyAlpha(p.textSecondary(), a));
        }
    }

    private static void magnifier(NVGRenderer nvg, float cx, float cy, int color) {
        nvg.circleOutline(cx - 0.8f, cy - 0.8f, 3.2f, 1.2f, color);
        nvg.line(cx + 1.6f, cy + 1.6f, cx + 3.6f, cy + 3.6f, 1.3f, color);
    }

    private static void keycap(NVGRenderer nvg, Palette p, float x, float y, String label, float a) {
        float tw = nvg.textWidth(Fonts.UI_MEDIUM, label, 6.5f);
        float w = Math.max(11f, tw + 6f);
        float kx = x + 11f - w;
        nvg.roundedRect(kx, y, w, 12f, 3f, Argb.multiplyAlpha(Argb.withAlpha(p.text(), 0.08f), a));
        nvg.rectOutline(kx, y, w, 12f, 3f, 0.8f, Argb.multiplyAlpha(Argb.withAlpha(p.border(), 0.6f), a));
        nvg.text(Fonts.UI_MEDIUM, label, kx + (w - tw) / 2f, y + 3f, 6.5f, Argb.multiplyAlpha(p.textMuted(), a));
    }
}
