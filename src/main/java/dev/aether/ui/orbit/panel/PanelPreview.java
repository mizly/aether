package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.Rect;

// the style picker's thumbnail: aurora's window, sidebar and card grid in miniature, drawn at a fixed scale
final class PanelPreview {
    private static final Icon[] ITEMS = {Icon.item("wheat"), Icon.item("silverfish_spawn_egg"), Icon.item("composter"),
            Icon.item("fishing_rod"), Icon.item("totem_of_undying")};

    private PanelPreview() {
    }

    static void draw(PanelFrame f, Rect r) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        c.save();
        c.clip(r);
        c.roundedRect(r, 10f, Argb.mix(p.panel(), 0xFF000000, p.light() ? 0.05f : 0.35f));
        c.legacy(nvg -> nvg.radialGradient(r.x() + r.w() * 0.2f, r.y() + r.h() * 0.15f, 0f, r.w() * 0.8f,
                Argb.withAlpha(p.accent(), 0.30f), Argb.withAlpha(p.accent(), 0f)));
        Rect window = r.inset(r.w() * 0.07f, r.h() * 0.1f, r.w() * 0.07f, r.h() * 0.1f);
        c.shadow(window.offset(0f, 3f), 8f, 14f, PanelPaint.shadow(p, 0.8f));
        c.roundedRect(window, 8f, PanelPaint.windowFill(p));
        c.strokeRect(window, 8f, 1f, Argb.withAlpha(p.border(), 0.3f));
        float sideW = window.w() * 0.24f;
        Rect side = new Rect(window.x(), window.y(), sideW, window.h());
        c.save();
        c.clip(side);
        c.roundedRect(new Rect(side.x(), side.y(), side.w() + 8f, side.h()), 8f, PanelPaint.sidebarFill(p));
        c.restore();
        float unit = window.h() / 14f;
        c.circle(side.x() + unit * 1.1f, side.y() + unit * 1.1f, unit * 0.45f, p.accent());
        c.roundedRect(new Rect(side.x() + unit * 1.9f, side.y() + unit * 0.85f, side.w() * 0.45f, unit * 0.5f),
                unit * 0.25f, Argb.withAlpha(p.text(), 0.7f));
        c.roundedRect(new Rect(side.x() + unit * 0.6f, side.y() + unit * 2.1f, side.w() - unit * 1.2f, unit * 0.9f),
                unit * 0.45f, Argb.withAlpha(p.text(), 0.07f));
        float y = side.y() + unit * 3.6f;
        for (int i = 0; i < ITEMS.length; i++) {
            Rect row = new Rect(side.x() + unit * 0.5f, y, side.w() - unit, unit * 1.1f);
            if (i == 1) {
                c.roundedRect(row, unit * 0.35f, PanelPaint.accentWash(p, 1.4f));
            }
            PanelPaint.icon(c, ITEMS[i], row.x() + unit * 0.75f, row.centerY(), unit * 0.85f, p.text());
            c.roundedRect(new Rect(row.x() + unit * 1.5f, row.centerY() - unit * 0.15f, row.w() * 0.45f, unit * 0.3f),
                    unit * 0.15f, Argb.withAlpha(p.text(), i == 1 ? 0.75f : 0.35f));
            y += unit * 1.35f;
        }
        Rect main = new Rect(side.right(), window.y(), window.right() - side.right(), window.h());
        c.line(main.x(), main.y() + unit * 2.2f, main.right(), main.y() + unit * 2.2f, 1f, PanelPaint.hairline(p));
        c.roundedRect(new Rect(main.x() + unit, main.y() + unit * 0.85f, main.w() * 0.3f, unit * 0.5f), unit * 0.25f,
                Argb.withAlpha(p.text(), 0.6f));
        c.roundedRect(new Rect(main.right() - unit * 4.2f, main.y() + unit * 0.6f, unit * 3.2f, unit * 1f), unit * 0.5f,
                p.accent());
        float gap = unit * 0.6f;
        float cardW = (main.w() - unit * 2f - gap) / 2f;
        float cardH = unit * 2.6f;
        for (int i = 0; i < 6; i++) {
            Rect card = new Rect(main.x() + unit + (i % 2) * (cardW + gap), main.y() + unit * 3f + (i / 2) * (cardH + gap),
                    cardW, cardH);
            c.roundedRect(card, unit * 0.5f, PanelPaint.cardFill(p));
            c.strokeRect(card, unit * 0.5f, 1f, PanelPaint.cardBorder(p));
            c.roundedRect(new Rect(card.x() + unit * 0.5f, card.y() + unit * 0.5f, unit * 1.2f, unit * 1.2f), unit * 0.3f,
                    Argb.withAlpha(p.text(), 0.08f));
            c.roundedRect(new Rect(card.x() + unit * 2.1f, card.y() + unit * 0.75f, card.w() * 0.35f, unit * 0.35f),
                    unit * 0.17f, Argb.withAlpha(p.text(), 0.55f));
            Rect sw = new Rect(card.right() - unit * 1.7f, card.y() + unit * 0.55f, unit * 1.2f, unit * 0.7f);
            PanelPaint.toggle(c, p, sw, i % 3 == 0 ? 0f : 1f, 0f, true);
        }
        c.restore();
    }
}
