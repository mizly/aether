package dev.aether.ui.gui;

import dev.aether.ui.util.Fonts;

import java.util.List;

// a test scene shown until the real styles are registered: it exercises the canvas, hit regions, focus,
// scrolling, icons and animation so the preview harness and the tests have something to drive
final class DebugStyle implements GuiStyle {
    private static final String FONT = Fonts.REGULAR;
    private static final String BOLD = Fonts.BOLD;
    private static final List<String> NAV = List.of("Overview", "Scrolling", "Icons");
    private static final int ROWS = 40;
    private static final float ROW_H = 28f;

    private final ScrollState scroll = new ScrollState();
    private int selectedNav;
    private int selectedRow = -1;
    private boolean switchOn = true;
    private float thumbGrab;

    @Override
    public String id() {
        return "debug";
    }

    @Override
    public String displayName() {
        return "Debug";
    }

    @Override
    public void render(GuiFrame f) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        Rect view = f.bounds();
        float w = Math.min(1040f, view.w() - 48f);
        float h = Math.min(640f, view.h() - 48f);
        Rect window = new Rect(view.centerX() - w / 2f, view.centerY() - h / 2f, w, h);
        c.shadow(window, 14f, 30f, p.shadow());
        c.roundedRect(window, 14f, p.panel());
        c.strokeRect(window, 14f, 1f, p.border());
        f.hits().block(window);

        header(f, window);
        sidebar(f, new Rect(window.x() + 10f, window.y() + 72f, 180f, window.h() - 82f));
        Rect content = Rect.ofEdges(window.x() + 202f, window.y() + 72f, window.right() - 14f, window.bottom() - 40f);
        float cardW = (content.w() - 20f) / 3f;
        switchCard(f, new Rect(content.x(), content.y(), cardW, 64f));
        iconCard(f, new Rect(content.x() + cardW + 10f, content.y(), cardW, 64f));
        alphaCard(f, new Rect(content.x() + (cardW + 10f) * 2f, content.y(), cardW, 64f));
        textCard(f, new Rect(content.x(), content.y() + 74f, content.w(), 62f));
        list(f, Rect.ofEdges(content.x(), content.y() + 146f, content.right(), content.bottom()));
        status(f, new Rect(window.x() + 202f, window.bottom() - 32f, window.w() - 216f, 20f));
    }

    @Override
    public void drawStylePreview(GuiFrame inert, Rect area) {
        GuiCanvas c = inert.canvas();
        Palette p = inert.palette();
        c.roundedRect(area, 6f, p.panel());
        c.roundedRect(new Rect(area.x() + 4f, area.y() + 4f, area.w() * 0.25f, area.h() - 8f), 4f, p.sidebar());
        for (int i = 0; i < 3; i++) {
            c.roundedRect(new Rect(area.x() + area.w() * 0.3f, area.y() + 8f + i * 14f, area.w() * 0.6f, 9f), 3f, p.card());
        }
    }

    private void header(GuiFrame f, Rect window) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        float t = f.textScale();
        c.icon(Icon.svg("/assets/aether/icons/logo.svg"), window.x() + 20f, window.y() + 18f, 28f, p.accent());
        c.text(BOLD, 18f * t, "Aether", window.x() + 58f, window.y() + 15f, p.text());
        String detail = "debug scene · " + Math.round(f.bounds().w()) + " x " + Math.round(f.bounds().h())
                + (p.light() ? " · light theme" : " · dark theme");
        c.text(FONT, 12f * t, detail, window.x() + 58f, window.y() + 38f, p.textMuted());
        c.rect(new Rect(window.x(), window.y() + 64f, window.w(), 1f), p.separator());
    }

    private void sidebar(GuiFrame f, Rect area) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        c.roundedRect(area, 10f, p.sidebar());
        float pillY = f.anim().spring("debug.nav.pill", area.y() + 8f + selectedNav * 40f);
        c.roundedRect(new Rect(area.x() + 6f, pillY, area.w() - 12f, 34f), 8f, Argb.withAlpha(p.accent(), 0.2f));
        for (int i = 0; i < NAV.size(); i++) {
            int index = i;
            Rect row = new Rect(area.x() + 6f, area.y() + 8f + i * 40f, area.w() - 12f, 34f);
            Object id = "debug.nav." + i;
            f.hits().add(id, row, HitHandler.click(() -> selectedNav = index), Cursor.HAND);
            float hover = f.anim().hover(id, f.hits().hovered(id));
            c.roundedRect(row, 8f, Argb.multiplyAlpha(p.hover(), hover));
            int color = i == selectedNav ? p.accent() : p.textValue();
            c.text(FONT, 13f * f.textScale(), NAV.get(i), row.x() + 12f, row.y() + 9f, color);
        }
    }

    private void switchCard(GuiFrame f, Rect card) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        f.hits().add("debug.switch", card, HitHandler.click(() -> switchOn = !switchOn), Cursor.HAND);
        c.roundedRect(card, 10f, p.card());
        c.roundedRect(card, 10f, Argb.multiplyAlpha(p.hover(), f.anim().hover("debug.switch", f.hits().hovered("debug.switch"))));
        c.text(FONT, 13f * f.textScale(), "Animated switch", card.x() + 14f, card.y() + 12f, p.textLabel());
        c.text(FONT, 11f * f.textScale(), switchOn ? "on" : "off", card.x() + 14f, card.y() + 34f, p.textMuted());
        Rect track = new Rect(card.right() - 54f, card.centerY() - 10f, 38f, 20f);
        float on = f.anim().spring("debug.switch.knob", switchOn ? 1f : 0f);
        c.roundedRect(track, 10f, Argb.mix(p.toggleTrack(), p.accent(), on));
        c.circle(track.x() + 10f + on * 18f, track.centerY(), 7f, switchOn ? p.onAccent() : p.toggleKnob());
    }

    private void iconCard(GuiFrame f, Rect card) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        c.roundedRect(card, 10f, p.card());
        c.text(FONT, 13f * f.textScale(), "Icons", card.x() + 14f, card.y() + 12f, p.textLabel());
        float x = card.x() + 14f;
        for (String svg : List.of("search", "settings", "play")) {
            c.icon(Icon.svg("/assets/aether/icons/" + svg + ".svg"), x, card.y() + 32f, 18f, p.textValue());
            x += 26f;
        }
        for (String item : List.of("wheat", "compass")) {
            c.icon(Icon.item(item), x, card.y() + 30f, 22f, 0xFFFFFFFF);
            x += 28f;
        }
        // a chevron as a path, the way glyph-like marks are drawn
        c.beginPath();
        c.moveTo(x + 2f, card.y() + 37f);
        c.lineTo(x + 7f, card.y() + 42f);
        c.lineTo(x + 12f, card.y() + 37f);
        c.strokePath(1.6f, p.textValue());
    }

    private void alphaCard(GuiFrame f, Rect card) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        c.roundedRect(card, 10f, p.card());
        c.text(FONT, 13f * f.textScale(), "Nested alpha", card.x() + 14f, card.y() + 12f, p.textLabel());
        c.save();
        c.alpha(0.5f);
        c.roundedRect(new Rect(card.x() + 14f, card.y() + 34f, 40f, 18f), 5f, p.accent());
        c.save();
        c.alpha(0.5f);
        c.roundedRect(new Rect(card.x() + 62f, card.y() + 34f, 40f, 18f), 5f, p.accent());
        c.restore();
        c.restore();
    }

    private void textCard(GuiFrame f, Rect card) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        float size = 12f * f.textScale();
        c.roundedRect(card, 10f, p.card());
        String sentence = "Ellipsized and wrapped literal text: the canvas measures with an LRU keyed by font, size and text, "
                + "so long labels never overflow their cards at any width or text scale.";
        float maxW = card.w() - 28f;
        c.text(FONT, size, c.ellipsize(FONT, size, sentence, maxW), card.x() + 14f, card.y() + 10f, p.textValue());
        List<String> lines = c.wrap(FONT, size, sentence, maxW * 0.6f);
        c.text(FONT, size, lines.getFirst() + "  (" + lines.size() + " wrapped lines)", card.x() + 14f,
                card.y() + 12f + c.lineHeight(FONT, size) + 4f, p.textMuted());
    }

    private void list(GuiFrame f, Rect viewport) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        scroll.setExtent(ROWS * ROW_H, viewport.h());
        scroll.tick(f.nanos(), f.anim().animTimeMs(), f.anim().snapping());
        c.roundedRect(viewport, 10f, p.card());
        f.hits().add("debug.list", viewport, new HitHandler() {
            @Override
            public boolean scroll(PointerEvent e, double dy) {
                scroll.scrollBy((float) (-dy * 40.0));
                return true;
            }
        });
        c.save();
        c.clip(viewport.inset(4f));
        c.translate(viewport.x(), viewport.y() - scroll.offset());
        for (int i = 0; i < ROWS; i++) {
            row(f, i, new Rect(6f, i * ROW_H + 4f, viewport.w() - 22f, ROW_H - 4f));
        }
        c.restore();
        scrollbar(f, new Rect(viewport.right() - 10f, viewport.y() + 6f, 4f, viewport.h() - 12f));
    }

    private void row(GuiFrame f, int index, Rect row) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        Object id = "debug.row." + index;
        if (f.focus().add(id, row, () -> selectedRow = index)) {
            scroll.ensureVisible(row.y(), row.bottom(), 6f);
        }
        if (!c.isVisible(row)) {
            return;
        }
        f.hits().add(id, row, HitHandler.click(() -> selectedRow = index), Cursor.HAND);
        float hover = f.anim().hover(id, f.hits().hovered(id));
        c.roundedRect(row, 6f, index == selectedRow ? Argb.withAlpha(p.accent(), 0.18f) : Argb.multiplyAlpha(p.hover(), hover));
        if (f.focus().ringVisible(id)) {
            c.strokeRect(row, 6f, 1.5f, p.accent());
        }
        c.text(FONT, 12f * f.textScale(), "Row " + (index + 1), row.x() + 10f, row.y() + 6f, p.textValue());
    }

    private void scrollbar(GuiFrame f, Rect track) {
        Rect thumb = scroll.thumb(track, 24f);
        if (thumb.isEmpty()) {
            return;
        }
        f.hits().add("debug.scrollbar", track.inset(-4f, 0f, -4f, 0f), new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                Rect current = scroll.thumb(track, 24f);
                thumbGrab = current.contains(current.centerX(), e.localY()) ? e.localY() - current.y() : current.h() / 2f;
                scroll.dragThumb(e.localY(), thumbGrab, track, 24f);
                return true;
            }

            @Override
            public void drag(PointerEvent e) {
                scroll.dragThumb(e.localY(), thumbGrab, track, 24f);
            }
        }, Cursor.RESIZE_NS);
        boolean active = f.hits().active("debug.scrollbar") || f.hits().hovered("debug.scrollbar");
        f.canvas().roundedRect(thumb, 2f, Argb.withAlpha(f.palette().textSecondary(), active ? 0.8f : 0.5f));
    }

    private void status(GuiFrame f, Rect line) {
        GuiCanvas c = f.canvas();
        float size = 11f * f.textScale();
        String text = "selected row " + (selectedRow + 1) + " · scroll " + Math.round(scroll.offset()) + " / "
                + Math.round(scroll.max()) + " · focus " + f.focus().focusedId();
        c.text(FONT, size, c.ellipsize(FONT, size, text, line.w()), line.x(), line.y(), f.palette().textMuted());
    }
}
