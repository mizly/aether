package dev.aether.ui.gui.skin;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.util.Fonts;

import java.util.List;
import java.util.Locale;

// every drawing primitive the gui needs, each with a working reference look (the aurora look). styles
// subclass and override what they restyle, so a primitive added later never breaks another skin.
// primitives draw only: controls own behaviour, hit regions and focus
public abstract class GuiSkin {
    private static final int WHITE = 0xFFFFFFFF;

    // -- layout ----------------------------------------------------------------

    public SkinMetrics metrics() {
        return SkinMetrics.reference();
    }

    public ListStyle listStyle() {
        return ListStyle.reference();
    }

    public RowLayout rowLayout(Setting setting) {
        return switch (setting.getType()) {
            case LIST, DROPDOWN_LIST, MULTI_DROPDOWN, POSITION -> RowLayout.STACKED;
            case TEXT -> setting instanceof TextSetting text && text.isMultiline()
                    ? RowLayout.STACKED : RowLayout.LABEL_LEFT_CONTROL_RIGHT;
            case INFO -> setting instanceof InfoSetting info && info.isMultiline()
                    ? RowLayout.STACKED : RowLayout.LABEL_LEFT_CONTROL_RIGHT;
            default -> RowLayout.LABEL_LEFT_CONTROL_RIGHT;
        };
    }

    // -- type ------------------------------------------------------------------

    public String font(FontRole role) {
        return switch (role) {
            case DISPLAY -> Fonts.UI_BOLD;
            case TITLE, HEADING, BUTTON, OVERLINE -> Fonts.UI_SEMIBOLD;
            case LABEL -> Fonts.UI_MEDIUM;
            case BODY, CAPTION -> Fonts.UI_REGULAR;
            case VALUE, MONO -> Fonts.UI_MONO;
        };
    }

    // base size before the text scale
    public float size(FontRole role) {
        return switch (role) {
            case DISPLAY -> 22f;
            case TITLE -> 16f;
            case HEADING -> 13.5f;
            case LABEL -> 13f;
            case BODY -> 12f;
            case CAPTION -> 11f;
            case BUTTON -> 12.5f;
            case VALUE, MONO -> 12f;
            case OVERLINE -> 10.5f;
        };
    }

    // overline text is upper-cased; everything else is drawn as given
    public String transform(FontRole role, String text) {
        return role == FontRole.OVERLINE ? text.toUpperCase(Locale.ROOT) : text;
    }

    public final float fontSize(SkinContext c, FontRole role) {
        return size(role) * c.textScale();
    }

    public float lineHeight(SkinContext c, FontRole role) {
        return c.canvas().lineHeight(font(role), fontSize(c, role));
    }

    public void text(SkinContext c, FontRole role, String text, float x, float y, int argb) {
        c.canvas().text(font(role), fontSize(c, role), transform(role, text), x, y, argb);
    }

    // vertically centred in r, left aligned and ellipsized to r's width
    public void textLeft(SkinContext c, FontRole role, String text, Rect r, int argb) {
        String shown = ellipsize(c, role, text, r.w());
        text(c, role, shown, r.x(), textTop(c, role, r), argb);
    }

    public void textRight(SkinContext c, FontRole role, String text, Rect r, int argb) {
        String shown = ellipsize(c, role, text, r.w());
        text(c, role, shown, r.right() - textWidth(c, role, shown), textTop(c, role, r), argb);
    }

    public void textCentered(SkinContext c, FontRole role, String text, Rect r, int argb) {
        String shown = ellipsize(c, role, text, r.w());
        text(c, role, shown, r.centerX() - textWidth(c, role, shown) / 2f, textTop(c, role, r), argb);
    }

    // the y that centres a line of this role in r
    public float textTop(SkinContext c, FontRole role, Rect r) {
        return r.centerY() - lineHeight(c, role) / 2f;
    }

    public float textWidth(SkinContext c, FontRole role, String text) {
        return c.canvas().textWidth(font(role), fontSize(c, role), transform(role, text));
    }

    public String ellipsize(SkinContext c, FontRole role, String text, float maxWidth) {
        return c.canvas().ellipsize(font(role), fontSize(c, role), transform(role, text), maxWidth);
    }

    public List<String> wrap(SkinContext c, FontRole role, String text, float maxWidth) {
        return c.canvas().wrap(font(role), fontSize(c, role), transform(role, text), Math.max(1f, maxWidth));
    }

    // -- colours ---------------------------------------------------------------

    public int toneColor(Palette p, Tone tone) {
        return switch (tone) {
            case NEUTRAL -> p.textMuted();
            case ACCENT -> p.accent();
            case SUCCESS -> p.success();
            case WARNING -> p.warning();
            case DANGER -> p.danger();
        };
    }

    // a 1 px line colour derived from the text colour, so it reads on light and dark panels alike
    protected int hairline(Palette p, float strength) {
        return Argb.withAlpha(p.text(), strength);
    }

    // -- surfaces and marks ----------------------------------------------------

    public void surface(SkinContext c, Rect r, Surface surface, float hoverT) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        SkinMetrics m = metrics();
        switch (surface) {
            case WINDOW -> {
                float radius = m.windowRadius();
                g.shadow(r, radius, 42f, p.shadow());
                g.roundedRect(r, radius, Argb.withAlpha(p.panel(), 0.94f));
                g.strokeRect(r, radius, c.pixel(), hairline(p, 0.09f));
                topHighlight(c, r, radius);
            }
            case SIDEBAR -> g.roundedRect(r, m.windowRadius(), Argb.withAlpha(p.sidebar(), 0.55f));
            case HEADER -> g.roundedRect(r, m.surfaceRadius(), Argb.withAlpha(p.card(), 0.55f));
            case CARD -> {
                float radius = m.surfaceRadius();
                if (hoverT > 0f) {
                    g.shadow(r, radius, 18f, Argb.multiplyAlpha(p.shadow(), 0.8f * hoverT));
                }
                g.roundedRect(r, radius, p.card());
                if (hoverT > 0f) {
                    g.roundedRect(r, radius, Argb.multiplyAlpha(p.hover(), hoverT));
                }
                g.strokeRect(r, radius, c.pixel(), hairline(p, 0.07f + 0.08f * hoverT));
            }
            case GROUP -> {
                float radius = m.surfaceRadius();
                g.roundedRect(r, radius, Argb.mix(p.panel(), p.card(), 0.7f));
                g.strokeRect(r, radius, c.pixel(), hairline(p, 0.07f));
            }
            case ROW -> {
                if (hoverT > 0f) {
                    g.roundedRect(r, m.fieldRadius(), Argb.withAlpha(p.text(), 0.035f * hoverT));
                }
            }
            case POPOVER -> {
                float radius = m.fieldRadius() + 2f;
                g.shadow(r, radius, 26f, p.shadow());
                g.roundedRect(r, radius, Argb.mix(p.card(), p.element(), 0.3f));
                g.strokeRect(r, radius, c.pixel(), hairline(p, 0.11f));
                topHighlight(c, r, radius);
            }
            case TOOLTIP -> {
                float radius = m.fieldRadius();
                g.shadow(r, radius, 14f, p.shadow());
                g.roundedRect(r, radius, Argb.mix(p.surface(), p.element(), 0.25f));
                g.strokeRect(r, radius, c.pixel(), hairline(p, 0.11f));
            }
            case FIELD -> {
                g.roundedRect(r, m.fieldRadius(), p.field());
                g.strokeRect(r, m.fieldRadius(), c.pixel(), hairline(p, 0.07f + 0.07f * hoverT));
            }
            case INSET -> g.roundedRect(r, m.fieldRadius(), Argb.withAlpha(p.text(), 0.035f));
        }
    }

    // the 1 px light edge along the top of glass panels
    protected void topHighlight(SkinContext c, Rect r, float radius) {
        Palette p = c.palette();
        int color = p.light() ? Argb.withAlpha(WHITE, 0.7f) : hairline(p, 0.06f);
        float y = snap(c, r.y() + c.pixel());
        c.canvas().rect(new Rect(r.x() + radius, y, Math.max(0f, r.w() - radius * 2f), c.pixel()), color);
    }

    public void divider(SkinContext c, float x1, float x2, float y) {
        float snapped = snap(c, y);
        c.canvas().rect(new Rect(x1, snapped, Math.max(0f, x2 - x1), c.pixel()), hairline(c.palette(), 0.08f));
    }

    public void verticalDivider(SkinContext c, float x, float y1, float y2) {
        float snapped = snapX(c, x);
        c.canvas().rect(new Rect(snapped, y1, c.pixel(), Math.max(0f, y2 - y1)), hairline(c.palette(), 0.08f));
    }

    public void focusRing(SkinContext c, Rect r, float radius) {
        Palette p = c.palette();
        Rect ring = r.inset(-2f);
        c.canvas().shadow(ring, radius + 2f, 8f, Argb.withAlpha(p.accent(), 0.30f));
        c.canvas().strokeRect(ring, radius + 2f, 1.5f, Argb.withAlpha(p.accent(), 0.9f));
    }

    // the highlight behind the selected navigation item or tab; t fades it in
    public void selection(SkinContext c, Rect r, float t) {
        if (t <= 0f) {
            return;
        }
        Palette p = c.palette();
        c.canvas().roundedRect(r, metrics().fieldRadius(), Argb.withAlpha(p.accent(), 0.15f * t));
        float barH = Math.min(r.h() - 8f, 16f);
        c.canvas().roundedRect(new Rect(r.x(), r.centerY() - barH / 2f, 3f, barH), 1.5f,
                Argb.multiplyAlpha(p.accent(), t));
    }

    // a setting row's hover wash and the pulse that marks a row revealed from search
    public void rowHighlight(SkinContext c, Rect r, float hoverT, float flashT) {
        surface(c, r, Surface.ROW, hoverT);
        if (flashT > 0f) {
            c.canvas().roundedRect(r, metrics().fieldRadius(), Argb.withAlpha(c.palette().accent(), 0.22f * flashT));
        }
    }

    public void banner(SkinContext c, Rect r, Tone tone) {
        int color = toneColor(c.palette(), tone);
        float radius = metrics().fieldRadius() + 2f;
        c.canvas().roundedRect(r, radius, Argb.withAlpha(color, 0.11f));
        c.canvas().strokeRect(r, radius, c.pixel(), Argb.withAlpha(color, 0.32f));
    }

    public float badgeWidth(SkinContext c, String label) {
        return textWidth(c, FontRole.CAPTION, label) + 14f;
    }

    public void badge(SkinContext c, Rect r, String label, Tone tone) {
        Palette p = c.palette();
        int color = toneColor(p, tone);
        c.canvas().roundedRect(r, r.h() / 2f, Argb.withAlpha(color, tone == Tone.NEUTRAL ? 0.14f : 0.16f));
        int textColor = tone == Tone.NEUTRAL ? p.textSecondary() : color;
        textCentered(c, FontRole.CAPTION, label, r.inset(6f, 0f, 6f, 0f), textColor);
    }

    // a rounded square holding a page or category icon
    public void iconTile(SkinContext c, Rect r, Icon icon, Tone tone, float hoverT) {
        Palette p = c.palette();
        float radius = Math.min(r.w(), r.h()) * 0.28f;
        int base = tone == Tone.NEUTRAL ? Argb.withAlpha(p.text(), 0.06f + 0.03f * hoverT)
                : Argb.withAlpha(toneColor(p, tone), 0.15f + 0.05f * hoverT);
        c.canvas().roundedRect(r, radius, base);
        c.canvas().strokeRect(r, radius, c.pixel(), hairline(p, 0.06f));
        if (icon != null) {
            float size = Math.min(r.w(), r.h()) * 0.62f;
            int tint = icon instanceof Icon.Item ? WHITE : tone == Tone.NEUTRAL ? p.textSecondary() : toneColor(p, tone);
            c.canvas().icon(icon, r.centerX() - size / 2f, r.centerY() - size / 2f, size, tint);
        }
    }

    public float sectionHeaderHeight(SkinContext c, String title, String description, float width) {
        float h = Math.max(metrics().sectionHeaderHeight() - 12f, lineHeight(c, FontRole.OVERLINE) + 14f);
        if (description != null && !description.isBlank()) {
            h += wrap(c, FontRole.CAPTION, description, width).size() * lineHeight(c, FontRole.CAPTION) + 2f;
        }
        return h + 6f;
    }

    // overline title with a hairline running to the right, and the explicit description under it
    public void sectionHeader(SkinContext c, Rect r, String title, String description) {
        Palette p = c.palette();
        float lh = lineHeight(c, FontRole.OVERLINE);
        float y = r.y() + 12f;
        text(c, FontRole.OVERLINE, title, r.x(), y, p.textMuted());
        float tw = textWidth(c, FontRole.OVERLINE, title);
        if (tw + 12f < r.w()) {
            divider(c, r.x() + tw + 10f, r.right(), y + lh / 2f);
        }
        if (description != null && !description.isBlank()) {
            float dy = y + lh + 4f;
            float step = lineHeight(c, FontRole.CAPTION);
            for (String line : wrap(c, FontRole.CAPTION, description, r.w())) {
                text(c, FontRole.CAPTION, line, r.x(), dy, p.textMuted());
                dy += step;
            }
        }
    }

    public float groupHeaderHeight(SkinContext c, GroupHeader h, float width) {
        float textW = Math.max(40f, width - h.trailingWidth() - (h.icon() != null ? 26f : 0f));
        float height = 14f + lineHeight(c, FontRole.HEADING);
        if (!h.description().isBlank()) {
            height += 2f + Math.min(2, wrap(c, FontRole.CAPTION, h.description(), textW).size())
                    * lineHeight(c, FontRole.CAPTION);
        }
        return Math.max(metrics().groupHeaderHeight(), height + 12f);
    }

    // title, optional icon and up to two lines of description; trailing controls are placed by the caller
    public void groupHeader(SkinContext c, Rect r, GroupHeader h) {
        Palette p = c.palette();
        float x = r.x();
        if (h.icon() != null) {
            float size = 18f;
            int tint = h.icon() instanceof Icon.Item ? WHITE : h.enabled() ? p.accent() : p.textMuted();
            c.canvas().icon(h.icon(), x, r.y() + 14f, size, h.dimmed() ? Argb.multiplyAlpha(tint, 0.55f) : tint);
            x += 26f;
        }
        float textW = Math.max(20f, r.right() - h.trailingWidth() - x);
        float titleH = lineHeight(c, FontRole.HEADING);
        float descH = h.description().isBlank() ? 0f
                : 2f + Math.min(2, wrap(c, FontRole.CAPTION, h.description(), textW).size()) * lineHeight(c, FontRole.CAPTION);
        float y = r.y() + Math.max(12f, (r.h() - titleH - descH) / 2f);
        int titleColor = h.dimmed() ? p.textMuted() : p.text();
        text(c, FontRole.HEADING, ellipsize(c, FontRole.HEADING, h.title(), textW), x, y, titleColor);
        if (descH > 0f) {
            float dy = y + titleH + 2f;
            List<String> lines = wrap(c, FontRole.CAPTION, h.description(), textW);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                String line = i == 1 && lines.size() > 2
                        ? ellipsize(c, FontRole.CAPTION, lines.get(1) + " " + lines.get(2), textW) : lines.get(i);
                text(c, FontRole.CAPTION, line, x, dy, h.dimmed() ? p.textDim() : p.textMuted());
                dy += lineHeight(c, FontRole.CAPTION);
            }
        }
    }

    // the small dot beside a label whose value differs from its default
    public void modifiedMark(SkinContext c, float cx, float cy) {
        c.canvas().circle(cx, cy, 2.5f, c.palette().accent());
    }

    public void glyph(SkinContext c, Glyph glyph, float cx, float cy, float size, int argb) {
        Glyphs.draw(c.canvas(), glyph, cx, cy, size, Math.max(1.2f, size * 0.11f), argb);
    }

    public float keyCapWidth(SkinContext c, String key) {
        return textWidth(c, FontRole.MONO, key) * 0.88f + 10f;
    }

    // a keyboard hint such as "Ctrl F"
    public void keyCap(SkinContext c, Rect r, String key) {
        Palette p = c.palette();
        c.canvas().roundedRect(r, 4f, Argb.withAlpha(p.text(), 0.06f));
        c.canvas().strokeRect(r, 4f, c.pixel(), hairline(p, 0.12f));
        float size = fontSize(c, FontRole.MONO) * 0.88f;
        float w = c.canvas().textWidth(font(FontRole.MONO), size, key);
        float lh = c.canvas().lineHeight(font(FontRole.MONO), size);
        c.canvas().text(font(FontRole.MONO), size, key, r.centerX() - w / 2f, r.centerY() - lh / 2f, p.textMuted());
    }

    // -- controls --------------------------------------------------------------

    // an ios switch in r (the track); onT is the knob's spring position from 0 (off) to 1 (on)
    public void toggle(SkinContext c, Rect r, float onT, float hoverT, boolean pressed, boolean enabled) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        g.save();
        if (!enabled) {
            g.alpha(0.45f);
        }
        float t = clamp01(onT);
        float radius = r.h() / 2f;
        int track = Argb.mix(p.toggleTrack(), p.accent(), t);
        track = Argb.mix(track, p.text(), 0.07f * hoverT);
        g.roundedRect(r, radius, track);
        if (t < 1f) {
            g.strokeRect(r, radius, c.pixel(), hairline(p, 0.10f * (1f - t)));
        }
        float inset = 2f;
        float d = r.h() - inset * 2f;
        float stretch = pressed ? 4f : 0f;
        float knobW = d + stretch;
        float travel = r.w() - inset * 2f - knobW;
        Rect knob = new Rect(r.x() + inset + travel * t, r.y() + inset, knobW, d);
        g.shadow(knob, d / 2f, 4f, Argb.multiplyAlpha(p.shadow(), 0.55f));
        g.roundedRect(knob, d / 2f, Argb.mix(p.toggleKnob(), WHITE, 0.25f + 0.75f * t));
        g.restore();
    }

    public void checkbox(SkinContext c, Rect r, float onT, float hoverT, boolean enabled) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        g.save();
        if (!enabled) {
            g.alpha(0.45f);
        }
        float t = clamp01(onT);
        float radius = Math.max(3f, r.w() * 0.28f);
        g.roundedRect(r, radius, p.field());
        g.strokeRect(r.inset(0.5f), radius, 1.25f, hairline(p, 0.24f + 0.14f * hoverT));
        if (t > 0f) {
            g.roundedRect(r, radius, Argb.multiplyAlpha(p.accent(), t));
            Glyphs.draw(g, Glyph.CHECK, r.centerX(), r.centerY() + 0.5f, r.w() * 0.95f, 1.8f,
                    Argb.multiplyAlpha(p.onAccent(), t));
        }
        g.restore();
    }

    public float buttonWidth(SkinContext c, String label, Icon icon) {
        float w = label == null || label.isEmpty() ? 0f : textWidth(c, FontRole.BUTTON, label);
        if (icon != null) {
            w += metrics().iconSize() + (w > 0f ? 6f : 0f);
        }
        return w + 24f;
    }

    public void button(SkinContext c, Rect r, String label, ButtonKind kind, Icon icon, float hoverT,
                       boolean pressed, boolean enabled) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        float radius = metrics().buttonRadius();
        g.save();
        if (!enabled) {
            g.alpha(0.42f);
            hoverT = 0f;
            pressed = false;
        }
        int textColor;
        switch (kind) {
            case PRIMARY -> {
                if (hoverT > 0f) {
                    g.shadow(r, radius, 12f, Argb.withAlpha(p.accent(), 0.30f * hoverT));
                }
                int fill = Argb.mix(p.accent(), p.onAccent(), 0.10f * hoverT);
                g.roundedRect(r, radius, pressed ? Argb.multiplyAlpha(fill, 0.82f) : fill);
                g.verticalGradient(r, radius, Argb.withAlpha(WHITE, 0.10f), Argb.withAlpha(WHITE, 0f));
                textColor = p.onAccent();
            }
            case SECONDARY -> {
                int fill = Argb.mix(p.action(), p.actionHover(), hoverT);
                g.roundedRect(r, radius, fill);
                if (pressed) {
                    g.roundedRect(r, radius, p.pressed());
                }
                g.strokeRect(r, radius, c.pixel(), hairline(p, 0.09f + 0.06f * hoverT));
                textColor = p.text();
            }
            case GHOST -> {
                if (hoverT > 0f || pressed) {
                    g.roundedRect(r, radius, pressed ? p.pressed() : Argb.multiplyAlpha(p.hover(), hoverT));
                }
                g.strokeRect(r, radius, c.pixel(), hairline(p, 0.12f + 0.08f * hoverT));
                textColor = Argb.mix(p.textSecondary(), p.text(), hoverT);
            }
            case DANGER -> {
                g.roundedRect(r, radius, Argb.withAlpha(p.danger(), 0.14f + 0.10f * hoverT + (pressed ? 0.08f : 0f)));
                g.strokeRect(r, radius, c.pixel(), Argb.withAlpha(p.danger(), 0.40f + 0.25f * hoverT));
                textColor = p.danger();
            }
            default -> textColor = Argb.mix(p.accent(), p.text(), 0.25f * hoverT);
        }
        float iconSize = metrics().iconSize();
        float labelW = label == null || label.isEmpty() ? 0f : textWidth(c, FontRole.BUTTON, label);
        float contentW = Math.min(r.w() - 12f, labelW + (icon != null ? iconSize + (labelW > 0f ? 6f : 0f) : 0f));
        float x = r.centerX() - contentW / 2f;
        if (icon != null) {
            int tint = icon instanceof Icon.Item ? WHITE : textColor;
            g.icon(icon, x, r.centerY() - iconSize / 2f, iconSize, tint);
            x += iconSize + 6f;
        }
        if (labelW > 0f) {
            float room = r.right() - 6f - x;
            String shown = ellipsize(c, FontRole.BUTTON, label, room);
            text(c, FontRole.BUTTON, shown, x, textTop(c, FontRole.BUTTON, r), textColor);
            if (kind == ButtonKind.LINK && hoverT > 0f) {
                float w = textWidth(c, FontRole.BUTTON, shown);
                float y = textTop(c, FontRole.BUTTON, r) + lineHeight(c, FontRole.BUTTON) - 1f;
                g.rect(new Rect(x, y, w, 1f), Argb.multiplyAlpha(textColor, hoverT));
            }
        }
        g.restore();
    }

    // a square button holding a glyph, for list editors and header tools
    public void iconButton(SkinContext c, Rect r, Glyph glyph, float hoverT, boolean pressed, boolean enabled) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        float radius = metrics().fieldRadius() - 1f;
        g.save();
        if (!enabled) {
            g.alpha(0.38f);
            hoverT = 0f;
        }
        g.roundedRect(r, radius, Argb.mix(p.field(), p.accent(), 0.10f * hoverT));
        if (pressed) {
            g.roundedRect(r, radius, p.pressed());
        }
        g.strokeRect(r, radius, c.pixel(), Argb.mix(hairline(p, 0.08f), Argb.withAlpha(p.accent(), 0.55f), hoverT));
        float size = Math.min(r.w(), r.h()) * 0.62f;
        Glyphs.draw(g, glyph, r.centerX(), r.centerY(), size, 1.5f, Argb.mix(p.textSecondary(), p.accent(), hoverT));
        g.restore();
    }

    // a text, number or value box; focused gets the accent edge and glow
    public void field(SkinContext c, Rect r, boolean focused, float hoverT, boolean invalid) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        float radius = metrics().fieldRadius();
        int edge = invalid ? p.danger() : p.accent();
        if (focused || invalid) {
            g.shadow(r, radius, 8f, Argb.withAlpha(edge, focused ? 0.28f : 0.18f));
        }
        g.roundedRect(r, radius, p.field());
        if (focused || invalid) {
            g.strokeRect(r.inset(0.5f), radius, 1.25f, Argb.withAlpha(edge, 0.9f));
        } else {
            g.strokeRect(r, radius, c.pixel(), hairline(p, 0.08f + 0.10f * hoverT));
        }
    }

    public void textSelection(SkinContext c, Rect r) {
        c.canvas().roundedRect(r, 2f, c.palette().selection());
    }

    public void caret(SkinContext c, Rect r) {
        c.canvas().rect(new Rect(r.x(), r.y(), Math.max(1f, r.w()), r.h()), c.palette().accent());
    }

    // r is the full track band; the bar is drawn centred in it with the range from..to filled
    public void sliderTrack(SkinContext c, Rect r, float from, float to, float hoverT, boolean enabled) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        g.save();
        if (!enabled) {
            g.alpha(0.45f);
        }
        float h = metrics().sliderTrackHeight() + hoverT;
        Rect bar = new Rect(r.x(), r.centerY() - h / 2f, r.w(), h);
        g.roundedRect(bar, h / 2f, Argb.mix(p.toggleTrack(), p.text(), 0.05f * hoverT));
        float left = r.x() + r.w() * clamp01(Math.min(from, to));
        float right = r.x() + r.w() * clamp01(Math.max(from, to));
        if (right - left > 0.5f) {
            g.horizontalGradient(new Rect(left, bar.y(), right - left, h), h / 2f, p.sliderLeft(), p.accent());
        }
        g.restore();
    }

    public void sliderKnob(SkinContext c, float cx, float cy, float hoverT, boolean pressed, boolean enabled) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        g.save();
        if (!enabled) {
            g.alpha(0.45f);
        }
        float radius = metrics().sliderKnobSize() / 2f + (pressed ? 1f : hoverT * 0.5f);
        if (pressed || hoverT > 0f) {
            g.circle(cx, cy, radius + 5f, Argb.withAlpha(p.accent(), pressed ? 0.22f : 0.12f * hoverT));
        }
        Rect knob = new Rect(cx - radius, cy - radius, radius * 2f, radius * 2f);
        g.shadow(knob, radius, 4f, Argb.multiplyAlpha(p.shadow(), 0.6f));
        g.circle(cx, cy, radius, p.light() ? WHITE : Argb.mix(p.text(), WHITE, 0.4f));
        g.strokeCircle(cx, cy, radius - 0.5f, 1f, Argb.withAlpha(p.accent(), 0.35f + 0.4f * hoverT));
        g.restore();
    }

    // a closed dropdown: optional option icon, the value (or "unknown: X" in muted text) and a chevron
    public void dropdownField(SkinContext c, Rect r, String value, Icon icon, boolean open, float hoverT,
                              boolean enabled, boolean unknown) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        float radius = metrics().fieldRadius();
        g.save();
        if (!enabled) {
            g.alpha(0.45f);
            hoverT = 0f;
        }
        if (open) {
            g.shadow(r, radius, 8f, Argb.withAlpha(p.accent(), 0.22f));
        }
        g.roundedRect(r, radius, Argb.mix(p.dropdown(), p.text(), 0.04f * hoverT));
        if (open) {
            g.strokeRect(r.inset(0.5f), radius, 1.25f, Argb.withAlpha(p.accent(), 0.9f));
        } else {
            g.strokeRect(r, radius, c.pixel(), hairline(p, 0.09f + 0.10f * hoverT));
        }
        float x = r.x() + 10f;
        float iconSize = metrics().iconSize();
        if (icon != null) {
            g.icon(icon, x, r.centerY() - iconSize / 2f, iconSize, icon instanceof Icon.Item ? WHITE : p.textSecondary());
            x += iconSize + 7f;
        }
        float chevronW = 24f;
        Rect textRect = Rect.ofEdges(x, r.y(), r.right() - chevronW, r.bottom());
        textLeft(c, FontRole.BODY, value, textRect, unknown ? p.warning() : p.textValue());
        Glyphs.draw(g, open ? Glyph.CHEVRON_UP : Glyph.CHEVRON_DOWN, r.right() - chevronW / 2f - 3f, r.centerY(),
                11f, 1.5f, Argb.mix(p.textMuted(), p.text(), hoverT));
        g.restore();
    }

    public float chipWidth(SkinContext c, String label, Icon icon) {
        return textWidth(c, FontRole.BODY, label) + 22f + (icon != null ? metrics().iconSize() + 5f : 0f);
    }

    public void chip(SkinContext c, Rect r, String label, Icon icon, boolean selected, float hoverT, boolean enabled) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        g.save();
        if (!enabled) {
            g.alpha(0.45f);
            hoverT = 0f;
        }
        float radius = r.h() / 2f;
        if (selected) {
            g.roundedRect(r, radius, Argb.withAlpha(p.accent(), 0.17f + 0.06f * hoverT));
            g.strokeRect(r, radius, c.pixel(), Argb.withAlpha(p.accent(), 0.60f));
        } else {
            g.roundedRect(r, radius, Argb.mix(p.field(), p.text(), 0.05f * hoverT));
            g.strokeRect(r, radius, c.pixel(), hairline(p, 0.10f + 0.10f * hoverT));
        }
        float x = r.x() + 11f;
        if (icon != null) {
            float size = metrics().iconSize();
            g.icon(icon, x, r.centerY() - size / 2f, size, icon instanceof Icon.Item ? WHITE : p.textSecondary());
            x += size + 5f;
        }
        int color = selected ? Argb.mix(p.accent(), p.text(), 0.15f) : Argb.mix(p.textSecondary(), p.text(), hoverT);
        textLeft(c, FontRole.BODY, label, Rect.ofEdges(x, r.y(), r.right() - 10f, r.bottom()), color);
        g.restore();
    }

    // alpha is not shown: the swatch is the opaque colour, like the old menu
    public void swatch(SkinContext c, Rect r, int argb, boolean active, float hoverT) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        float radius = Math.min(6f, r.w() * 0.28f);
        if (active) {
            g.strokeRect(r.inset(-3f), radius + 3f, 1.5f, Argb.withAlpha(p.accent(), 0.95f));
        }
        g.roundedRect(r, radius, argb | 0xFF000000);
        g.strokeRect(r, radius, c.pixel(), hairline(p, 0.22f + 0.2f * hoverT));
    }

    public void scrollbar(SkinContext c, Rect track, Rect thumb, float hoverT, boolean dragging) {
        if (thumb.isEmpty()) {
            return;
        }
        Palette p = c.palette();
        float t = dragging ? 1f : hoverT;
        float w = Math.min(track.w(), 4f + 2f * t);
        float x = track.right() - w - (track.w() - w) / 2f;
        if (t > 0f) {
            c.canvas().roundedRect(new Rect(x, track.y(), w, track.h()), w / 2f, Argb.withAlpha(p.text(), 0.04f * t));
        }
        c.canvas().roundedRect(new Rect(x, thumb.y(), w, thumb.h()), w / 2f,
                Argb.withAlpha(p.text(), 0.20f + 0.16f * t));
    }

    public float tooltipPadding() {
        return 10f;
    }

    // width and height (x and y are 0) of a tooltip for this text wrapped at maxWidth
    public Rect tooltipSize(SkinContext c, String title, List<String> body, float maxWidth) {
        float pad = tooltipPadding();
        float inner = maxWidth - pad * 2f;
        float w = 0f;
        float h = 0f;
        if (title != null && !title.isEmpty()) {
            for (String line : wrap(c, FontRole.LABEL, title, inner)) {
                w = Math.max(w, textWidth(c, FontRole.LABEL, line));
                h += lineHeight(c, FontRole.LABEL);
            }
        }
        boolean gap = h > 0f;
        for (String paragraph : body) {
            for (String line : wrap(c, FontRole.BODY, paragraph, inner)) {
                w = Math.max(w, textWidth(c, FontRole.BODY, line));
                h += lineHeight(c, FontRole.BODY) + 1f;
            }
        }
        if (gap && !body.isEmpty()) {
            h += 4f;
        }
        return new Rect(0f, 0f, Math.min(maxWidth, w + pad * 2f), h + pad * 2f - 1f);
    }

    public void tooltip(SkinContext c, Rect r, String title, List<String> body) {
        surface(c, r, Surface.TOOLTIP, 0f);
        Palette p = c.palette();
        float pad = tooltipPadding();
        float inner = r.w() - pad * 2f;
        float y = r.y() + pad;
        if (title != null && !title.isEmpty()) {
            for (String line : wrap(c, FontRole.LABEL, title, inner)) {
                text(c, FontRole.LABEL, line, r.x() + pad, y, p.text());
                y += lineHeight(c, FontRole.LABEL);
            }
            y += 4f;
        }
        for (String paragraph : body) {
            for (String line : wrap(c, FontRole.BODY, paragraph, inner)) {
                text(c, FontRole.BODY, line, r.x() + pad, y, p.textSecondary());
                y += lineHeight(c, FontRole.BODY) + 1f;
            }
        }
    }

    // t is the open progress; overlays draw their panel with this
    public void popover(SkinContext c, Rect r, float t) {
        surface(c, r, Surface.POPOVER, 0f);
    }

    // one row of a dropdown or context menu
    public void menuRow(SkinContext c, Rect r, String label, Icon icon, boolean selected, float hoverT,
                        boolean enabled, boolean destructive) {
        GuiCanvas g = c.canvas();
        Palette p = c.palette();
        float radius = metrics().fieldRadius() - 2f;
        if (selected) {
            g.roundedRect(r, radius, Argb.withAlpha(p.accent(), 0.14f));
        }
        if (hoverT > 0f && enabled) {
            g.roundedRect(r, radius, Argb.withAlpha(p.text(), 0.07f * hoverT));
        }
        float x = r.x() + 10f;
        float iconSize = metrics().iconSize();
        if (icon != null) {
            int tint = icon instanceof Icon.Item ? WHITE : destructive ? p.danger() : p.textSecondary();
            g.icon(icon, x, r.centerY() - iconSize / 2f, iconSize, enabled ? tint : Argb.multiplyAlpha(tint, 0.45f));
            x += iconSize + 8f;
        }
        int color = !enabled ? p.textDim() : destructive ? p.danger() : selected ? p.text()
                : Argb.mix(p.textValue(), p.text(), hoverT);
        float right = r.right() - (selected ? 26f : 10f);
        textLeft(c, FontRole.BODY, label, Rect.ofEdges(x, r.y(), right, r.bottom()), color);
        if (selected) {
            Glyphs.draw(g, Glyph.CHECK, r.right() - 15f, r.centerY(), 12f, 1.6f, p.accent());
        }
    }

    public void menuSeparator(SkinContext c, Rect r) {
        divider(c, r.x() + 8f, r.right() - 8f, r.centerY());
    }

    // -- helpers ---------------------------------------------------------------

    // rounds a local y onto a whole device pixel, so 1 px lines stay crisp at fractional ui scales
    protected float snap(SkinContext c, float localY) {
        GuiCanvas g = c.canvas();
        float device = 1f / Math.max(0.01f, c.pixel());
        float rootY = g.toRoot(new Rect(0f, localY, 0f, 0f)).y();
        return g.toLocalY(Math.round(rootY * device) / device);
    }

    protected float snapX(SkinContext c, float localX) {
        GuiCanvas g = c.canvas();
        float device = 1f / Math.max(0.01f, c.pixel());
        float rootX = g.toRoot(new Rect(localX, 0f, 0f, 0f)).x();
        return g.toLocalX(Math.round(rootX * device) / device);
    }

    protected static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
