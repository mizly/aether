package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.Rect;
import dev.aether.util.AetherLang;

import static dev.aether.ui.orbit.panel.PanelPaint.MEDIUM;
import static dev.aether.ui.orbit.panel.PanelPaint.SEMIBOLD;

// breadcrumb with icons on the left, the session strip with resume and the close button on the right
final class PanelHeader {
    private final PanelStyle style;

    PanelHeader(PanelStyle style) {
        this.style = style;
    }

    void draw(PanelFrame f, Rect header, PanelNav.Category category, PanelNav.Page page) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        style.sidebar.dragArea(f, header);
        c.line(header.x(), header.bottom() - 0.5f, header.right(), header.bottom() - 0.5f, 1f, PanelPaint.hairline(p));

        float right = header.right() - 16f;
        Rect close = new Rect(right - 32f, header.centerY() - 16f, 32f, 32f);
        float hoverClose = f.anim().hover("aurora.close", f.hits().hovered("aurora.close"));
        c.roundedRect(close, 9f, Argb.withAlpha(p.danger(), hoverClose * 0.16f));
        PanelPaint.cross(c, close.centerX(), close.centerY(), 10f, 1.6f, Argb.mix(p.textMuted(), p.danger(), hoverClose));
        f.hits().add("aurora.close", close, HitHandler.click(() -> f.host().close()), Cursor.HAND);
        right = close.x() - 10f;

        float crumbsEnd = drawCrumbs(f, header, category, page, right);
        drawSession(f, header, crumbsEnd + 16f, right);
    }

    private float drawCrumbs(PanelFrame f, Rect header, PanelNav.Category category, PanelNav.Page page, float limit) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        float x = header.x() + 24f;
        float cy = header.centerY();
        boolean atHome = category == null;
        String homeId = "aurora.crumb.home";
        float homeHover = f.anim().hover(homeId, f.hits().hovered(homeId));
        Rect homeRect = new Rect(x - 6f, cy - 15f, atHome ? 0f : 30f, 30f);
        int homeColor = atHome ? p.text() : Argb.mix(p.textMuted(), p.text(), homeHover);
        if (!atHome && homeHover > 0.01f) {
            c.roundedRect(homeRect, 8f, Argb.multiplyAlpha(p.hover(), homeHover * 1.5f));
        }
        PanelPaint.home(c, x + 9f, cy, 15f, 1.6f, homeColor);
        x += 26f;
        if (atHome) {
            c.text(SEMIBOLD, 16f, AetherLang.localize("Home"), x, PanelPaint.top(cy, 16f), p.text());
            return x + c.textWidth(SEMIBOLD, 16f, AetherLang.localize("Home"));
        }
        f.hits().add(homeId, homeRect, HitHandler.click(style::goHome), Cursor.HAND);
        x = separator(c, p, x, cy);
        boolean categoryLast = page == null;
        String catId = "aurora.crumb.cat";
        float catHover = f.anim().hover(catId, f.hits().hovered(catId));
        float nameW = c.textWidth(categoryLast ? SEMIBOLD : MEDIUM, categoryLast ? 16f : 13f, category.name());
        Rect catRect = new Rect(x - 6f, cy - 15f, nameW + 38f, 30f);
        if (!categoryLast && catHover > 0.01f) {
            c.roundedRect(catRect, 8f, Argb.multiplyAlpha(p.hover(), catHover * 1.5f));
        }
        PanelPaint.icon(c, category.icon(), x + 9f, cy, 18f, p.text());
        x += 26f;
        if (categoryLast) {
            c.text(SEMIBOLD, 16f, category.name(), x, PanelPaint.top(cy, 16f), p.text());
            return x + nameW;
        }
        c.text(MEDIUM, 13f, category.name(), x, PanelPaint.top(cy, 13f), Argb.mix(p.textMuted(), p.text(), catHover));
        f.hits().add(catId, catRect, HitHandler.click(() -> style.openCategory(category.id())), Cursor.HAND);
        x += nameW;
        x = separator(c, p, x, cy);
        PanelPaint.icon(c, page.icon(), x + 9f, cy, 18f, p.text());
        x += 26f;
        float max = Math.max(60f, limit - x - 200f);
        String name = c.ellipsize(SEMIBOLD, 16f, page.name(), max);
        c.text(SEMIBOLD, 16f, name, x, PanelPaint.top(cy, 16f), p.text());
        return x + c.textWidth(SEMIBOLD, 16f, name);
    }

    private static float separator(GuiCanvas c, Palette p, float x, float cy) {
        PanelPaint.chevronRight(c, x + 9f, cy, 9f, 1.5f, Argb.withAlpha(p.textMuted(), 0.8f));
        return x + 24f;
    }

    private void drawSession(PanelFrame f, Rect header, float left, float right) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        PanelHost.Session session = f.host().session();
        if (session == null || !session.resumable()) {
            return;
        }
        String label = AetherLang.localize("Resume");
        float bw = c.textWidth(SEMIBOLD, 12.5f, label) + 46f;
        Rect resume = new Rect(right - bw, header.centerY() - 16f, bw, 32f);
        float hover = f.anim().hover("aurora.resume", f.hits().hovered("aurora.resume"));
        PanelPaint.resumeButton(c, p, resume, label, hover);
        f.hits().add("aurora.resume", resume, HitHandler.click(() -> f.host().resume()), Cursor.HAND);

        String status = AetherLang.localize(session.macroName()) + " · " + duration(session.sessionMs());
        float space = resume.x() - 14f - left;
        float statusW = c.textWidth(MEDIUM, 12f, status);
        if (space < 90f) {
            return;
        }
        float textX = Math.max(left, resume.x() - 14f - Math.min(statusW, space - 14f));
        float dotX = textX - 12f;
        int dot = session.failsafe() != null ? p.danger() : p.warning();
        c.circle(dotX, header.centerY(), 3.5f, dot);
        c.circle(dotX, header.centerY(), 6.5f, Argb.withAlpha(dot, 0.22f));
        PanelPaint.fitText(c, MEDIUM, 12f, status, textX, header.centerY(), resume.x() - 14f - textX, p.textSecondary());
    }

    static String duration(long millis) {
        long minutes = Math.max(0L, millis) / 60_000L;
        if (minutes < 60L) {
            return minutes + "m";
        }
        return (minutes / 60L) + "h " + (minutes % 60L) + "m";
    }

    static String sessionLine(PanelHost.Session session) {
        StringBuilder line = new StringBuilder(AetherLang.localize("Session") + " " + duration(session.sessionMs()));
        if (session.restInMs() >= 0L) {
            line.append(" · ").append(AetherLang.localize("next rest in")).append(' ').append(duration(session.restInMs()));
        }
        return line.toString();
    }
}
