package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.ScrollState;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

import static dev.aether.ui.orbit.panel.PanelPaint.BOLD;
import static dev.aether.ui.orbit.panel.PanelPaint.MEDIUM;
import static dev.aether.ui.orbit.panel.PanelPaint.REGULAR;
import static dev.aether.ui.orbit.panel.PanelPaint.SEMIBOLD;

// one module: a header card with the page switch, sticky scroll-spy anchor pills, then the setting groups
final class PanelModulePage {
    private static final float PAD = 24f;
    private static final float PILLS_H = 44f;

    private final PanelStyle style;
    private List<PanelRows.Anchor> anchors = List.of();
    private String anchorsPage;
    private String pendingAnchor;
    private float pillScroll;
    private float pillContentW;

    PanelModulePage(PanelStyle style) {
        this.style = style;
    }

    void revealAnchor(String anchorKey) {
        pendingAnchor = anchorKey;
    }

    void draw(PanelFrame f, Rect body, PanelNav.Page page) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        ScrollState scroll = style.scroll("aurora.page." + page.id());
        scroll.tick(f.nanos(), 250f, f.frozen());
        f.hits().add("aurora.page.scroll", body, new HitHandler() {
            @Override
            public boolean scroll(PointerEvent e, double dy) {
                scroll.scrollBy((float) (-dy * 48.0));
                return true;
            }
        });

        float x = body.x() + PAD;
        float w = body.w() - PAD * 2f;
        float origin = body.y() - scroll.offset();
        String enterKey = style.enterKey();
        float enter = f.anim().stagger(enterKey + ".head", 0);

        c.save();
        c.clip(body);
        float y = origin + 20f;
        c.save();
        c.alpha(enter);
        c.translate(0f, (1f - enter) * 8f);
        float headH = drawHeaderCard(f, page, new Rect(x, y, w, 0f));
        c.restore();
        y += headH + 12f;
        if (page.hasToggle() && !page.enabled() && !page.runtime()) {
            y += drawBanner(f, page, new Rect(x, y, w, 46f)) + 12f;
        }
        float pillsTop = y - origin;
        if (expectedAnchors(page) >= 2) {
            y += PILLS_H + 6f;
        }
        List<PanelRows.Anchor> found = new ArrayList<>();
        boolean pageOn = !page.hasToggle() || page.enabled() || page.runtime();
        float bottom = style.rows.draw(f, page.id(), page.tab().groups(), x, y, w, pageOn, origin, found, enterKey);
        if (found.isEmpty()) {
            PanelPaint.textCentered(c, REGULAR, 13f, AetherLang.localize("This page has no settings yet"),
                    body.centerX(), y + 40f, p.textMuted());
            bottom = y + 80f;
        }
        anchors = found;
        anchorsPage = page.id();
        float stuckY = Math.max(body.y(), origin + pillsTop);
        drawPills(f, scroll, new Rect(body.x(), stuckY, body.w(), PILLS_H), stuckY <= body.y() + 0.5f);
        c.restore();

        scroll.setExtent(bottom - origin + 28f, body.h());
        if (pendingAnchor != null) {
            for (PanelRows.Anchor anchor : found) {
                if (anchor.key().equals(pendingAnchor) || anchor.label().equals(pendingAnchor)) {
                    scroll.scrollTo(anchor.y() - PILLS_H - 10f);
                    break;
                }
            }
            pendingAnchor = null;
        }
        drawScrollbar(c, p, scroll, body);
    }

    // last frame's anchors for this page, or a count of its groups on the first frame after navigating
    private int expectedAnchors(PanelNav.Page page) {
        if (page.id().equals(anchorsPage)) {
            return anchors.size();
        }
        int groups = 0;
        for (var group : page.tab().groups()) {
            if (group.hasSettings() || !group.isAlwaysOn()) {
                groups++;
            }
        }
        return groups;
    }

    private float drawHeaderCard(PanelFrame f, PanelNav.Page page, Rect area) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        float textX = area.x() + 92f;
        float controlW = page.hasToggle() ? (page.runtime() ? 130f : 70f) : 0f;
        float textW = area.w() - 92f - 24f - controlW;
        List<String> lines = c.wrap(REGULAR, 12.5f, page.description(), textW);
        if (lines.size() > 2) {
            lines = List.of(lines.get(0), c.ellipsize(REGULAR, 12.5f, lines.get(1) + " " + lines.get(2), textW));
        }
        float h = Math.max(96f, 44f + lines.size() * 17f + 20f);
        Rect card = new Rect(area.x(), area.y(), area.w(), h);
        c.shadow(card.offset(0f, 2f), 14f, 16f, PanelPaint.shadow(p, 0.35f));
        c.roundedRect(card, 14f, PanelPaint.cardFill(p));
        c.horizontalGradient(card, 14f, Argb.withAlpha(p.accent(), 0.13f), Argb.withAlpha(p.accent(), 0f));
        c.strokeRect(card, 14f, 1f, PanelPaint.cardBorder(p));
        c.line(card.x() + 14f, card.y() + 0.5f, card.right() - 14f, card.y() + 0.5f, 1f, PanelPaint.highlight(p));
        Rect tile = new Rect(card.x() + 20f, card.centerY() - 28f, 56f, 56f);
        PanelPaint.iconTile(c, p, tile, page.icon(), 32f, 14f);
        float titleY = card.y() + (h - (24f + lines.size() * 17f)) / 2f;
        c.text(BOLD, 20f, c.ellipsize(BOLD, 20f, page.name(), textW), textX, titleY, p.text());
        float ly = titleY + 28f;
        for (String line : lines) {
            c.text(REGULAR, 12.5f, line, textX, ly, p.textSecondary());
            ly += 17f;
        }
        if (page.hasToggle()) {
            String id = "aurora.page.toggle." + page.id();
            float hover = f.anim().hover(id, f.hits().hovered(id));
            if (page.runtime()) {
                String label = AetherLang.localize(page.enabled() ? "Turn off" : "Turn on");
                Rect button = new Rect(card.right() - 20f - 120f, card.centerY() - 16f, 120f, 32f);
                PanelPaint.button(c, p, button, label, null,
                        page.enabled() ? PanelPaint.ButtonKind.GHOST : PanelPaint.ButtonKind.PRIMARY, hover, false, true);
                f.hits().add(id, button, HitHandler.click(() -> page.tab().toggle()), Cursor.HAND);
            } else {
                Rect sw = new Rect(card.right() - 20f - 46f, card.centerY() - 13f, 46f, 26f);
                float on = f.anim().spring(id + ".knob", page.enabled() ? 1f : 0f);
                PanelPaint.toggle(c, p, sw, on, hover, true);
                String state = AetherLang.localize(page.enabled() ? "On" : "Off");
                PanelPaint.textCentered(c, SEMIBOLD, 10.5f, state, sw.centerX(), sw.bottom() + 12f,
                        page.enabled() ? Argb.mix(p.accent(), p.text(), 0.3f) : p.textMuted());
                f.hits().add(id, sw.inset(-6f), HitHandler.click(() -> page.tab().toggle()), Cursor.HAND);
            }
        }
        return h;
    }

    private float drawBanner(PanelFrame f, PanelNav.Page page, Rect r) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        c.roundedRect(r, 12f, Argb.withAlpha(p.warning(), p.light() ? 0.16f : 0.12f));
        c.strokeRect(r, 12f, 1f, Argb.withAlpha(p.warning(), 0.35f));
        c.circle(r.x() + 20f, r.centerY(), 4f, p.warning());
        String text = AetherLang.localize("Off") + " — " + AetherLang.localize("changes apply when enabled");
        PanelPaint.fitText(c, MEDIUM, 12.5f, text, r.x() + 34f, r.centerY(), r.w() - 160f, p.text());
        String label = AetherLang.localize("Enable");
        float bw = c.textWidth(SEMIBOLD, 12.5f, label) + 30f;
        Rect button = new Rect(r.right() - bw - 8f, r.centerY() - 15f, bw, 30f);
        String id = "aurora.page.enable." + page.id();
        PanelPaint.button(c, p, button, label, null, PanelPaint.ButtonKind.PRIMARY,
                f.anim().hover(id, f.hits().hovered(id)), false, true);
        f.hits().add(id, button, HitHandler.click(() -> page.tab().enabledSetter().accept(true)), Cursor.HAND);
        return r.h();
    }

    private void drawPills(PanelFrame f, ScrollState scroll, Rect bar, boolean stuck) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        List<PanelRows.Anchor> list = anchors;
        if (list.size() < 2) {
            return;
        }
        if (stuck) {
            c.rect(bar, Argb.withAlpha(p.panel(), 0.96f));
            c.line(bar.x(), bar.bottom() - 0.5f, bar.right(), bar.bottom() - 0.5f, 1f, PanelPaint.hairline(p));
        }
        f.hits().block(bar);
        int active = 0;
        float probe = scroll.offset() + PILLS_H + 30f;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).y() <= probe) {
                active = i;
            }
        }
        if (scroll.offset() >= scroll.max() - 1f && scroll.max() > 0f) {
            active = list.size() - 1;
        }
        Rect lane = bar.inset(PAD, 0f, PAD, 0f);
        float[] lefts = new float[list.size()];
        float[] widths = new float[list.size()];
        float cursor = 0f;
        for (int i = 0; i < list.size(); i++) {
            PanelRows.Anchor anchor = list.get(i);
            String label = c.ellipsize(MEDIUM, 12f, anchor.label(), 180f);
            widths[i] = c.textWidth(MEDIUM, 12f, label) + (anchor.section() ? 20f : 26f);
            lefts[i] = cursor;
            cursor += widths[i] + 6f;
        }
        pillContentW = cursor - 6f;
        float maxScroll = Math.max(0f, pillContentW - lane.w());
        float activeLeft = lefts[active];
        float activeRight = activeLeft + widths[active];
        if (activeLeft - pillScroll < 0f) {
            pillScroll = activeLeft;
        } else if (activeRight - pillScroll > lane.w()) {
            pillScroll = activeRight - lane.w();
        }
        pillScroll = Math.max(0f, Math.min(maxScroll, pillScroll));
        float shift = f.anim().spring("aurora.pills.scroll", pillScroll);
        f.hits().add("aurora.pills", bar, new HitHandler() {
            @Override
            public boolean scroll(PointerEvent e, double dy) {
                pillScroll = Math.max(0f, Math.min(maxScroll, pillScroll - (float) dy * 40f));
                return true;
            }
        });
        c.save();
        c.clip(lane);
        float selLeft = f.anim().spring("aurora.pills.sel.x", lefts[active]);
        float selW = f.anim().spring("aurora.pills.sel.w", widths[active]);
        Rect sel = new Rect(lane.x() + selLeft - shift, bar.centerY() - 14f, selW, 28f);
        c.roundedRect(sel, 14f, PanelPaint.accentWash(p, 1.3f));
        c.strokeRect(sel, 14f, 1f, Argb.withAlpha(p.accent(), 0.45f));
        for (int i = 0; i < list.size(); i++) {
            PanelRows.Anchor anchor = list.get(i);
            Rect pill = new Rect(lane.x() + lefts[i] - shift, bar.centerY() - 14f, widths[i], 28f);
            String id = "aurora.pill." + anchor.key();
            boolean hover = f.hits().hovered(id);
            if (i != active) {
                c.roundedRect(pill, 14f, Argb.withAlpha(p.text(), hover ? 0.09f : 0.045f));
            }
            String label = c.ellipsize(MEDIUM, 12f, anchor.label(), 180f);
            float tx = pill.x() + (anchor.section() ? 10f : 13f);
            int color = i == active ? p.text() : (anchor.section() ? p.textMuted() : p.textSecondary());
            if (anchor.section()) {
                c.circle(pill.x() + 7f, pill.centerY(), 1.6f, Argb.withAlpha(p.textMuted(), 0.8f));
                tx += 2f;
            }
            PanelPaint.text(c, MEDIUM, 12f, label, tx, pill.centerY(), color);
            float target = anchor.y();
            f.hits().add(id, pill, HitHandler.click(() -> scroll.scrollTo(target - PILLS_H - 10f)), Cursor.HAND);
        }
        c.restore();
        if (maxScroll > 0f) {
            int fadeColor = Argb.withAlpha(p.panel(), 0.95f);
            if (shift > 1f) {
                c.horizontalGradient(new Rect(lane.x(), bar.y(), 24f, bar.h()), 0f, fadeColor, Argb.withAlpha(p.panel(), 0f));
            }
            if (shift < maxScroll - 1f) {
                c.horizontalGradient(new Rect(lane.right() - 24f, bar.y(), 24f, bar.h()), 0f,
                        Argb.withAlpha(p.panel(), 0f), fadeColor);
            }
        }
    }

    static void drawScrollbar(GuiCanvas c, Palette p, ScrollState scroll, Rect body) {
        if (scroll.max() <= 0f) {
            return;
        }
        Rect track = new Rect(body.right() - 7f, body.y() + 6f, 3f, body.h() - 12f);
        Rect thumb = scroll.thumb(track, 32f);
        c.roundedRect(thumb, 1.5f, Argb.withAlpha(p.text(), 0.20f));
    }
}
