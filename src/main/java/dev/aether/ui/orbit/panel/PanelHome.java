package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.ScrollState;
import dev.aether.util.AetherLang;

import java.util.List;

import static dev.aether.ui.orbit.panel.PanelPaint.BOLD;
import static dev.aether.ui.orbit.panel.PanelPaint.MEDIUM;
import static dev.aether.ui.orbit.panel.PanelPaint.REGULAR;
import static dev.aether.ui.orbit.panel.PanelPaint.SEMIBOLD;

// the dashboard (session, pins, recent changes, category tiles) and a category's landing grid of page cards
final class PanelHome {
    private static final float PAD = 24f;
    private static final float GAP = 12f;
    private static final Icon CLOCK = Icon.svg("/assets/aether/icons/clock.svg");
    private static final Icon WARNING = Icon.svg("/assets/aether/icons/warning.svg");

    private final PanelStyle style;

    PanelHome(PanelStyle style) {
        this.style = style;
    }

    // -- home ---------------------------------------------------------------------

    void drawHome(PanelFrame f, Rect body) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        ScrollState scroll = scrollArea(f, body, "aurora.home");
        float x = body.x() + PAD;
        float w = body.w() - PAD * 2f;
        float origin = body.y() - scroll.offset();
        String enter = style.enterKey();
        int index = 0;
        c.save();
        c.clip(body);
        float y = origin + 22f;

        PanelHost.Account account = f.host().account();
        String name = f.host().streamerMode() || account.state() != PanelHost.AuthState.SIGNED_IN ? null : account.name();
        String greeting = name == null ? AetherLang.localize("Welcome back") : AetherLang.localize("Welcome back") + ", " + name;
        float e = enterStep(f, enter, index++);
        c.save();
        c.alpha(e);
        c.translate(0f, (1f - e) * 8f);
        c.text(BOLD, 22f, c.ellipsize(BOLD, 22f, greeting, w), x, y, p.text());
        c.text(REGULAR, 13f, AetherLang.localize("Your macro, pests, garden and display settings in one place"), x,
                y + 30f, p.textMuted());
        c.restore();
        y += 62f;

        e = enterStep(f, enter, index++);
        c.save();
        c.alpha(e);
        c.translate(0f, (1f - e) * 8f);
        y += drawSessionCard(f, new Rect(x, y, w, 84f)) + GAP;
        y += drawFailsafe(f, new Rect(x, y, w, 40f));
        c.restore();

        e = enterStep(f, enter, index++);
        c.save();
        c.alpha(e);
        c.translate(0f, (1f - e) * 8f);
        float colW = w >= 560f ? (w - GAP) / 2f : w;
        Rect pins = new Rect(x, y, colW, 132f);
        Rect changes = w >= 560f ? new Rect(x + colW + GAP, y, colW, 132f) : new Rect(x, y + 132f + GAP, w, 132f);
        drawEmptyCard(f, pins, AetherLang.localize("Pinned settings"),
                AetherLang.localize("Pin any setting from its menu to keep it here"), "pin");
        drawEmptyCard(f, changes, AetherLang.localize("Recent changes"),
                AetherLang.localize("Settings you change show up here, ready to undo"), "changes");
        c.restore();
        y = changes.bottom() + 24f;

        c.text(SEMIBOLD, 15f, AetherLang.localize("Categories"), x, y, p.text());
        y += 28f;
        List<PanelNav.Category> categories = style.nav.categories();
        int cols = columns(w, 220f, 4);
        float tileW = (w - GAP * (cols - 1)) / cols;
        float tileH = 84f;
        for (int i = 0; i < categories.size(); i++) {
            PanelNav.Category category = categories.get(i);
            Rect tile = new Rect(x + (i % cols) * (tileW + GAP), y + (i / cols) * (tileH + GAP), tileW, tileH);
            drawCategoryTile(f, category, tile, enterStep(f, enter, index + i));
        }
        int rows = (categories.size() + cols - 1) / cols;
        y += rows * (tileH + GAP);
        c.restore();
        scroll.setExtent(y - origin + 16f, body.h());
        PanelModulePage.drawScrollbar(c, p, scroll, body);
    }

    private float drawSessionCard(PanelFrame f, Rect card) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        PanelHost.Session session = f.host().session();
        boolean resumable = session != null && session.resumable();
        c.shadow(card.offset(0f, 2f), 14f, 16f, PanelPaint.shadow(p, 0.3f));
        c.roundedRect(card, 14f, PanelPaint.cardFill(p));
        if (resumable) {
            c.horizontalGradient(card, 14f, Argb.withAlpha(p.accent(), 0.16f), Argb.withAlpha(p.accent(), 0.02f));
        }
        c.strokeRect(card, 14f, 1f, resumable ? Argb.withAlpha(p.accent(), 0.35f) : PanelPaint.cardBorder(p));
        c.line(card.x() + 14f, card.y() + 0.5f, card.right() - 14f, card.y() + 0.5f, 1f, PanelPaint.highlight(p));
        Rect tile = new Rect(card.x() + 18f, card.centerY() - 24f, 48f, 48f);
        Icon icon = resumable && session.macroItem() != null ? Icon.item(session.macroItem()) : CLOCK;
        PanelPaint.iconTile(c, p, tile, icon, resumable ? 28f : 20f, 12f);
        float tx = tile.right() + 16f;
        float buttonW = 0f;
        if (resumable) {
            String label = AetherLang.localize("Resume");
            buttonW = c.textWidth(SEMIBOLD, 13f, label) + 52f;
            Rect resume = new Rect(card.right() - 20f - buttonW, card.centerY() - 18f, buttonW, 36f);
            float hover = f.anim().hover("aurora.home.resume", f.hits().hovered("aurora.home.resume"));
            PanelPaint.resumeButton(c, p, resume, label, hover);
            f.hits().add("aurora.home.resume", resume, HitHandler.click(() -> f.host().resume()), Cursor.HAND);
            float capW = c.textWidth(MEDIUM, 10.5f, "Ctrl+Enter") + 12f;
            if (resume.x() - capW - 12f > tx + 160f) {
                PanelPaint.keycap(c, p, resume.x() - capW - 10f, card.centerY(), "Ctrl+Enter");
                buttonW += capW + 10f;
            }
            String title = AetherLang.localize(session.macroName()) + " " + (session.stoppedByMenu()
                    ? AetherLang.localize("stopped when you opened Aether") : AetherLang.localize("is ready to resume"));
            float textW = card.right() - 36f - buttonW - tx;
            PanelPaint.fitText(c, SEMIBOLD, 14f, title, tx, card.centerY() - 9f, textW, p.text());
            PanelPaint.fitText(c, REGULAR, 12f, PanelHeader.sessionLine(session), tx, card.centerY() + 11f, textW,
                    p.textMuted());
        } else {
            String title = AetherLang.localize("No macro running");
            PanelPaint.text(c, SEMIBOLD, 14f, title, tx, card.centerY() - 9f, p.text());
            PanelPaint.text(c, REGULAR, 12f, AetherLang.localize("Start one from the macro menu or with its keybind"),
                    tx, card.centerY() + 11f, p.textMuted());
            String label = AetherLang.localize("Open macros");
            float bw = c.textWidth(SEMIBOLD, 12.5f, label) + 32f;
            Rect open = new Rect(card.right() - 20f - bw, card.centerY() - 16f, bw, 32f);
            float hover = f.anim().hover("aurora.home.macros", f.hits().hovered("aurora.home.macros"));
            PanelPaint.button(c, p, open, label, null, PanelPaint.ButtonKind.GHOST, hover, false, true);
            f.hits().add("aurora.home.macros", open, HitHandler.click(() -> f.host().openMacroMenu()), Cursor.HAND);
        }
        return card.h();
    }

    // a recent failsafe trigger, deep-linking to where it is tuned
    private float drawFailsafe(PanelFrame f, Rect strip) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        PanelHost.Session session = f.host().session();
        if (session == null || session.failsafe() == null) {
            return 0f;
        }
        c.roundedRect(strip, 12f, Argb.withAlpha(p.danger(), p.light() ? 0.14f : 0.12f));
        c.strokeRect(strip, 12f, 1f, Argb.withAlpha(p.danger(), 0.35f));
        PanelPaint.icon(c, WARNING, strip.x() + 20f, strip.centerY(), 15f, p.danger());
        String text = AetherLang.localize(session.failsafe()) + " " + AetherLang.localize("failsafe fired") + " "
                + PanelHeader.duration(session.failsafeAgoMs()) + " " + AetherLang.localize("ago");
        String link = AetherLang.localize("Adjust");
        float linkW = c.textWidth(SEMIBOLD, 12f, link) + 30f;
        PanelPaint.fitText(c, MEDIUM, 12.5f, text, strip.x() + 36f, strip.centerY(), strip.w() - 60f - linkW, p.text());
        Rect adjust = new Rect(strip.right() - linkW - 6f, strip.centerY() - 13f, linkW, 26f);
        float hover = f.anim().hover("aurora.home.failsafe", f.hits().hovered("aurora.home.failsafe"));
        c.roundedRect(adjust, 9f, Argb.withAlpha(p.danger(), 0.10f + hover * 0.12f));
        PanelPaint.text(c, SEMIBOLD, 12f, link, adjust.x() + 10f, adjust.centerY(), p.danger());
        PanelPaint.chevronRight(c, adjust.right() - 11f, adjust.centerY(), 8f, 1.5f, p.danger());
        f.hits().add("aurora.home.failsafe", adjust, HitHandler.click(() -> style.openFailsafe(session.failsafe())),
                Cursor.HAND);
        return strip.h() + GAP;
    }

    private void drawEmptyCard(PanelFrame f, Rect card, String title, String hint, String glyph) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        PanelPaint.card(c, p, card, 14f, 0f);
        c.text(SEMIBOLD, 13.5f, title, card.x() + 18f, card.y() + 16f, p.text());
        float cy = card.y() + 76f;
        Rect badge = new Rect(card.centerX() - 16f, cy - 26f, 32f, 32f);
        c.roundedRect(badge, 16f, Argb.withAlpha(p.text(), 0.05f));
        if (glyph.equals("pin")) {
            c.circle(badge.centerX(), badge.centerY() - 3f, 4.5f, p.textMuted());
            c.line(badge.centerX(), badge.centerY() + 1f, badge.centerX(), badge.centerY() + 9f, 1.6f, p.textMuted());
        } else {
            PanelPaint.chevronLeft(c, badge.centerX() - 3f, badge.centerY(), 9f, 1.6f, p.textMuted());
            c.line(badge.centerX() - 4f, badge.centerY(), badge.centerX() + 6f, badge.centerY(), 1.6f, p.textMuted());
        }
        PanelPaint.textCentered(c, REGULAR, 12f, c.ellipsize(REGULAR, 12f, hint, card.w() - 32f), card.centerX(),
                cy + 26f, p.textMuted());
    }

    private void drawCategoryTile(PanelFrame f, PanelNav.Category category, Rect tile, float enter) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        String id = "aurora.home.cat." + category.id();
        float hover = f.anim().hover(id, f.hits().hovered(id));
        c.save();
        c.alpha(enter);
        c.translate(0f, (1f - enter) * 8f - hover * 2f);
        PanelPaint.card(c, p, tile, 14f, hover);
        Rect iconTile = new Rect(tile.x() + 16f, tile.centerY() - 22f, 44f, 44f);
        PanelPaint.iconTile(c, p, iconTile, category.icon(), 26f, 12f);
        float tx = iconTile.right() + 14f;
        PanelPaint.fitText(c, SEMIBOLD, 14f, category.name(), tx, tile.centerY() - 9f, tile.right() - tx - 30f, p.text());
        int toggleable = category.toggleableCount();
        String detail = toggleable > 0
                ? category.enabledCount() + " " + AetherLang.localize("of") + " " + toggleable + " " + AetherLang.localize("on")
                : category.pages().size() + " " + AetherLang.localize(category.pages().size() == 1 ? "page" : "pages");
        if (toggleable > 0 && category.enabledCount() > 0) {
            c.circle(tx + 3f, tile.centerY() + 11f, 3f, p.success());
            PanelPaint.text(c, REGULAR, 12f, detail, tx + 11f, tile.centerY() + 11f, p.textMuted());
        } else {
            PanelPaint.text(c, REGULAR, 12f, detail, tx, tile.centerY() + 11f, p.textMuted());
        }
        PanelPaint.chevronRight(c, tile.right() - 18f + hover * 2f, tile.centerY(), 9f, 1.6f,
                Argb.mix(p.textMuted(), p.text(), hover));
        c.restore();
        f.hits().add(id, tile, HitHandler.click(() -> style.openCategory(category.id())), Cursor.HAND);
    }

    // -- category landing -------------------------------------------------------------

    void drawCategory(PanelFrame f, Rect body, PanelNav.Category category) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        ScrollState scroll = scrollArea(f, body, "aurora.cat." + category.id());
        float x = body.x() + PAD;
        float w = body.w() - PAD * 2f;
        float origin = body.y() - scroll.offset();
        String enter = style.enterKey();
        c.save();
        c.clip(body);
        float y = origin + 22f;

        float e = enterStep(f, enter, 0);
        c.save();
        c.alpha(e);
        c.translate(0f, (1f - e) * 8f);
        Rect hero = new Rect(x, y, w, 84f);
        Rect tile = new Rect(hero.x(), hero.centerY() - 32f, 64f, 64f);
        c.shadow(tile.offset(0f, 3f), 16f, 18f, Argb.withAlpha(p.accent(), 0.25f));
        PanelPaint.iconTile(c, p, tile, category.icon(), 40f, 16f);
        float tx = tile.right() + 18f;
        int on = category.enabledCount();
        int toggleable = category.toggleableCount();
        String count = on + " " + AetherLang.localize("on");
        float countW = toggleable > 0 ? PanelPaint.pillWidth(c, count, SEMIBOLD, 12f, 12f) : 0f;
        c.text(BOLD, 24f, c.ellipsize(BOLD, 24f, category.name(), w - (tx - x) - countW - 16f), tx, hero.centerY() - 26f,
                p.text());
        PanelPaint.fitText(c, REGULAR, 13f, category.description(), tx, hero.centerY() + 14f, w - (tx - x) - countW - 16f,
                p.textSecondary());
        if (toggleable > 0) {
            PanelPaint.pill(c, hero.right() - countW, hero.centerY(), count, SEMIBOLD, 12f,
                    on > 0 ? Argb.mix(p.accent(), p.text(), 0.3f) : p.textMuted(),
                    on > 0 ? Argb.withAlpha(p.accent(), 0.18f) : Argb.withAlpha(p.text(), 0.06f), 12f, 26f);
        }
        c.restore();
        y += hero.h() + 20f;

        List<PanelNav.Page> pages = category.pages();
        if (pages.isEmpty()) {
            PanelPaint.textCentered(c, REGULAR, 13f, AetherLang.localize("Nothing here yet"), body.centerX(), y + 40f,
                    p.textMuted());
            y += 80f;
        }
        int cols = columns(w, 260f, 4);
        float cardW = (w - GAP * (cols - 1)) / cols;
        float cardH = 100f;
        for (int i = 0; i < pages.size(); i++) {
            Rect card = new Rect(x + (i % cols) * (cardW + GAP), y + (i / cols) * (cardH + GAP), cardW, cardH);
            drawPageCard(f, pages.get(i), card, enterStep(f, enter, 1 + i));
        }
        y += ((pages.size() + cols - 1) / cols) * (cardH + GAP);
        c.restore();
        scroll.setExtent(y - origin + 16f, body.h());
        PanelModulePage.drawScrollbar(c, p, scroll, body);
    }

    private void drawPageCard(PanelFrame f, PanelNav.Page page, Rect card, float enter) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        String id = "aurora.card." + page.id();
        String switchId = "aurora.card.switch." + page.id();
        boolean hoverCard = f.hits().hovered(id) || f.hits().hovered(switchId);
        float hover = f.anim().hover(id, hoverCard);
        boolean toggle = page.hasToggle();
        boolean on = toggle && page.enabled();
        c.save();
        c.alpha(enter);
        c.translate(0f, (1f - enter) * 8f - hover * 2f);
        if (toggle) {
            PanelPaint.card(c, p, card, 14f, hover);
            if (on && !page.runtime()) {
                c.horizontalGradient(card, 14f, Argb.withAlpha(p.accent(), 0.10f), Argb.withAlpha(p.accent(), 0f));
            }
        } else {
            if (hover > 0.01f) {
                c.shadow(card, 14f, 18f, PanelPaint.shadow(p, 0.5f * hover));
            }
            c.roundedRect(card, 14f, Argb.withAlpha(p.text(), 0.035f + hover * 0.03f));
            c.strokeRect(card, 14f, 1f, Argb.mix(Argb.withAlpha(p.border(), 0.18f), p.borderHover(), hover * 0.5f));
        }
        Rect tile = new Rect(card.x() + 16f, card.y() + 16f, 40f, 40f);
        PanelPaint.iconTile(c, p, tile, page.icon(), 24f, 11f);
        float tx = tile.right() + 14f;
        float right = card.right() - 16f;
        if (toggle && !page.runtime()) {
            Rect sw = new Rect(right - 36f, tile.y() + 2f, 36f, 20f);
            float knob = f.anim().spring(switchId + ".knob", on ? 1f : 0f);
            PanelPaint.toggle(c, p, sw, knob, f.hits().hovered(switchId) ? 1f : 0f, true);
            right = sw.x() - 10f;
        } else {
            PanelPaint.chevronRight(c, right - 6f + hover * 2f, tile.y() + 12f, 9f, 1.6f,
                    Argb.mix(p.textMuted(), p.text(), hover));
            right -= 22f;
        }
        PanelPaint.fitText(c, SEMIBOLD, 14f, page.name(), tx, tile.y() + 12f, right - tx, p.text());
        if (toggle && !page.runtime()) {
            String state = AetherLang.localize(on ? "On" : "Off");
            PanelPaint.text(c, MEDIUM, 11f, state, tx, tile.y() + 31f,
                    on ? Argb.mix(p.success(), p.text(), 0.15f) : p.textMuted());
        } else if (page.runtime()) {
            PanelPaint.text(c, MEDIUM, 11f, AetherLang.localize(on ? "Active" : "Inactive"), tx, tile.y() + 31f,
                    p.textMuted());
        }
        List<String> lines = c.wrap(REGULAR, 11.5f, page.description(), card.w() - 32f);
        float ly = tile.bottom() + 8f;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            String line = lines.get(i);
            if (i == 1 && lines.size() > 2) {
                line = c.ellipsize(REGULAR, 11.5f, line + " " + lines.get(2), card.w() - 32f);
            }
            c.text(REGULAR, 11.5f, line, card.x() + 16f, ly, p.textMuted());
            ly += 15f;
        }
        c.restore();
        f.hits().add(id, card, HitHandler.click(() -> style.openPage(page.id())), Cursor.HAND);
        if (toggle && !page.runtime()) {
            Rect sw = new Rect(card.right() - 16f - 36f, card.y() + 18f, 36f, 20f);
            f.hits().add(switchId, sw.inset(-6f), HitHandler.click(() -> page.tab().toggle()), Cursor.HAND);
        }
    }

    // -- helpers -----------------------------------------------------------------------

    private ScrollState scrollArea(PanelFrame f, Rect body, String key) {
        ScrollState scroll = style.scroll(key);
        scroll.tick(f.nanos(), 250f, f.frozen());
        f.hits().add(key + ".scroll", body, new HitHandler() {
            @Override
            public boolean scroll(PointerEvent e, double dy) {
                scroll.scrollBy((float) (-dy * 48.0));
                return true;
            }
        });
        return scroll;
    }

    private static float enterStep(PanelFrame f, String key, int index) {
        return f.anim().stagger(key, index);
    }

    static int columns(float width, float minWidth, int max) {
        int cols = (int) Math.floor((width + GAP) / (minWidth + GAP));
        return Math.max(2, Math.min(max, cols));
    }
}
