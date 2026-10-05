package dev.aether.ui.orbit;

import dev.aether.renderer.McIcons;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.orbit.panel.PanelHost;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

// the flat layer over the 3d scene: breadcrumb, the macro strip with resume, close, and the category tabs
final class OrbitOverlay {
    private record Button(float x, float y, float w, float h, Runnable action) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final OrbitScreen screen;
    private final PanelHost host;
    private final OrbitSearchBar search;
    private final List<Button> buttons = new ArrayList<>();

    OrbitOverlay(OrbitScreen screen, PanelHost host, OrbitSearchBar search) {
        this.screen = screen;
        this.host = host;
        this.search = search;
    }

    boolean hovering(double x, double y) {
        if (search.hovering(x, y)) return true;
        for (Button b : buttons) if (b.contains(x, y)) return true;
        return false;
    }

    boolean click(double x, double y, int button) {
        if (search.click(x, y)) return true;
        for (Button b : buttons) {
            if (b.contains(x, y)) {
                b.action().run();
                return true;
            }
        }
        return false;
    }

    void render(NVGRenderer nvg, float w, float h, float mx, float my) {
        buttons.clear();
        float a = screen.hudAlpha();
        if (a <= 0.01f) return;
        Palette p = Palette.fromTheme();
        int chip = Argb.multiplyAlpha(Argb.withAlpha(p.panel(), 0.88f), a);
        int border = Argb.multiplyAlpha(Argb.withAlpha(p.border(), 0.45f), a);
        int text = Argb.multiplyAlpha(p.text(), a);
        int muted = Argb.multiplyAlpha(p.textMuted(), a);
        float pad = 10f;

        // top left: brand and where you are
        String crumb = screen.breadcrumb();
        float brandW = 62f + (crumb.isEmpty() ? 0f : nvg.textWidth(Fonts.UI_MEDIUM, crumb, 8f) + 22f);
        nvg.roundedRect(pad, pad, brandW, 22f, 7f, chip);
        nvg.rectOutline(pad, pad, brandW, 22f, 7f, 1f, border);
        nvg.text(Fonts.UI_BOLD, "Aether", pad + 10f, pad + 6.5f, 9f, text);
        if (!crumb.isEmpty()) {
            nvg.rect(pad + 52f, pad + 6f, 1f, 10f, border);
            nvg.text(Fonts.UI_MEDIUM, crumb, pad + 60f, pad + 7f, 8f, muted);
        }

        // top right: close, and the macro the menu stopped with a resume button
        float leftEnd = pad + brandW + 8f;
        float x = w - pad - 22f;
        float rightStart = x;
        Button close = new Button(x, pad, 22f, 22f, screen::beginClose);
        boolean closeHover = close.contains(mx, my);
        nvg.roundedRect(x, pad, 22f, 22f, 7f, closeHover ? Argb.multiplyAlpha(Argb.withAlpha(p.text(), 0.16f), a) : chip);
        nvg.rectOutline(x, pad, 22f, 22f, 7f, 1f, border);
        cross(nvg, x + 11f, pad + 11f, 3.5f, text);
        buttons.add(close);
        PanelHost.Session session = host.session();
        if (session.resumable()) {
            String label = AetherLang.localize("Resume");
            float rw = nvg.textWidth(Fonts.UI_SEMIBOLD, label, 8.5f) + 22f;
            float rx = x - 6f - rw;
            Button resume = new Button(rx, pad, rw, 22f, host::resume);
            rightStart = rx;
            boolean hover = resume.contains(mx, my);
            nvg.roundedRect(rx, pad, rw, 22f, 7f, Argb.multiplyAlpha(hover ? Argb.mix(p.accent(), 0xFFFFFFFF, 0.12f) : p.accent(), a));
            triangle(nvg, rx + 9f, pad + 11f, 3.2f, Argb.multiplyAlpha(p.onAccent(), a));
            nvg.text(Fonts.UI_SEMIBOLD, label, rx + 15f, pad + 7f, 8.5f, Argb.multiplyAlpha(p.onAccent(), a));
            buttons.add(resume);
            String status = session.macroName() + " " + AetherLang.localize(session.stoppedByMenu() ? "paused" : "ready")
                    + " · " + duration(session.sessionMs());
            float sw = nvg.textWidth(Fonts.UI_MEDIUM, status, 7.5f) + 26f;
            float sx = rx - 6f - sw;
            // the status chip gives way first so a narrow window keeps room for search
            if (sx - 8f - leftEnd >= 140f) {
                rightStart = sx;
                nvg.roundedRect(sx, pad, sw, 22f, 7f, chip);
                nvg.rectOutline(sx, pad, sw, 22f, 7f, 1f, border);
                nvg.circle(sx + 10f, pad + 11f, 2.5f, Argb.multiplyAlpha(p.warning(), a));
                nvg.text(Fonts.UI_MEDIUM, status, sx + 17f, pad + 7.5f, 7.5f, muted);
            }
        }
        float room = rightStart - 8f - leftEnd;
        float searchW = room >= 90f ? Math.min(220f, room) : 22f;
        float searchX = Math.max(leftEnd, Math.min(rightStart - 8f - searchW, (w - searchW) / 2f));

        // bottom: one tab per category, the active one underlined, plus the key hints above
        List<String> ids = screen.categoryIds();
        float tabH = 22f;
        float gap = 2f;
        float total = 12f;
        float[] widths = new float[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            widths[i] = nvg.textWidth(Fonts.UI_MEDIUM, screen.view().orbitCategoryName(ids.get(i)), 8f) + 30f;
            total += widths[i] + gap;
        }
        float bx = (w - total) / 2f;
        float by = h - pad - tabH;
        nvg.roundedRect(bx, by, total, tabH, 8f, chip);
        nvg.rectOutline(bx, by, total, tabH, 8f, 1f, border);
        float tx = bx + 6f;
        int active = screen.activeIndex();
        boolean overview = screen.overview();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            float tw = widths[i];
            int index = i;
            Button tab = new Button(tx, by, tw, tabH, () -> {
                screen.spinTo(index);
                screen.setOverview(false);
            });
            boolean on = i == active && !overview;
            boolean hover = tab.contains(mx, my);
            if (hover) nvg.roundedRect(tx, by + 3f, tw, tabH - 6f, 5f, Argb.multiplyAlpha(Argb.withAlpha(p.text(), 0.07f), a));
            Icon icon = screen.view().orbitCategoryIcon(id);
            if (icon instanceof Icon.Item item) nvg.mcIcon(McIcons.of(item.id()), tx + 5f, by + 6f, 10f, Argb.multiplyAlpha(0xFFFFFFFF, a));
            nvg.text(Fonts.UI_MEDIUM, screen.view().orbitCategoryName(id), tx + 19f, by + 7.5f, 8f, on ? text : muted);
            if (on) nvg.rect(tx + 8f, by + tabH - 2.5f, tw - 16f, 1.5f, Argb.multiplyAlpha(p.accent(), a));
            buttons.add(tab);
            tx += tw + gap;
        }
        String hint = overview
                ? AetherLang.localize("Click a category to open it · Esc to close")
                : AetherLang.localize("Scroll or drag to spin · Tab for overview · Type to search · Esc back");
        float hw = nvg.textWidth(Fonts.UI_REGULAR, hint, 7f);
        nvg.text(Fonts.UI_REGULAR, hint, (w - hw) / 2f, by - 12f, 7f, Argb.multiplyAlpha(Argb.withAlpha(p.text(), 0.55f), a));
        search.render(nvg, p, searchX, pad, searchW, w, h, mx, my, a);
    }

    private static String duration(long ms) {
        long minutes = Math.max(0, ms) / 60000;
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    private static void cross(NVGRenderer nvg, float cx, float cy, float s, int color) {
        nvg.line(cx - s, cy - s, cx + s, cy + s, 1.3f, color);
        nvg.line(cx - s, cy + s, cx + s, cy - s, 1.3f, color);
    }

    private static void triangle(NVGRenderer nvg, float cx, float cy, float s, int color) {
        nvg.beginPath();
        nvg.moveTo(cx - s * 0.6f, cy - s);
        nvg.lineTo(cx + s, cy);
        nvg.lineTo(cx - s * 0.6f, cy + s);
        nvg.closePath();
        nvg.fillPath(color);
    }
}
