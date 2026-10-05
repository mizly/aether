package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.ScrollState;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SettingType;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

import static dev.aether.ui.orbit.panel.PanelPaint.BOLD;
import static dev.aether.ui.orbit.panel.PanelPaint.MEDIUM;
import static dev.aether.ui.orbit.panel.PanelPaint.REGULAR;
import static dev.aether.ui.orbit.panel.PanelPaint.SEMIBOLD;

// what one orbit panel shows: a category's modules with their essentials inline, an opened module, or an
// overview card when the ring is zoomed out
final class PanelOrbit {
    static final float RADIUS = 16f;
    private static final float HEADER_H = 58f;
    private static final float PAD = 14f;
    private static final int ESSENTIALS = 4;

    // each failsafe stands for something you would notice in game
    static final java.util.Map<String, String> FAILSAFE_ITEMS = java.util.Map.of(
            "GUI Opened", "minecraft:chest", "Rotation", "minecraft:compass", "World Change", "minecraft:ender_pearl",
            "Inventory Slot Changed", "minecraft:bundle", "BPS", "minecraft:golden_hoe", "Dirt Check", "minecraft:dirt",
            "Ghost Block", "minecraft:glass", "Player Nearby", "minecraft:player_head", "TP Check", "minecraft:chorus_fruit");

    private final PanelStyle style;
    private String hoveredFailsafe;
    private PanelNav.Page hoveredCard;

    PanelOrbit(PanelStyle style) {
        this.style = style;
    }

    void draw(PanelFrame f, Rect area, String categoryId, boolean active, float overview) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        PanelNav.Category category = style.nav.category(categoryId);
        if (category == null) return;
        c.roundedRect(area, RADIUS, PanelPaint.windowFill(p));
        c.strokeRect(area, RADIUS, 1f, PanelPaint.cardBorder(p));
        if (overview > 0.5f) {
            drawOverview(f, area, category);
            return;
        }
        PanelNav.Page page = active ? style.nav.page(style.location().pageId()) : null;
        Rect header = new Rect(area.x(), area.y(), area.w(), HEADER_H);
        Rect body = new Rect(area.x(), area.y() + HEADER_H, area.w(), area.h() - HEADER_H);
        c.save();
        c.clip(area);
        if (page != null && page.categoryId().equals(categoryId)) {
            style.modulePage.draw(f, body, page);
            drawHeader(f, header, category, page);
        } else {
            drawModules(f, body, category, active);
            drawHeader(f, header, category, null);
        }
        c.restore();
    }

    private void drawHeader(PanelFrame f, Rect header, PanelNav.Category category, PanelNav.Page page) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        c.save();
        c.clip(header);
        c.rect(header, PanelPaint.windowFill(p));
        c.restore();
        c.line(header.x(), header.bottom(), header.right(), header.bottom(), 1f, PanelPaint.hairline(p));
        float x = header.x() + 18f;
        float cy = header.centerY();
        if (page != null) {
            String id = "orbit.back";
            float hover = f.anim().hover(id, f.hits().hovered(id));
            float bw = c.textWidth(MEDIUM, 12.5f, AetherLang.localize("Back")) + 34f;
            Rect back = new Rect(x, cy - 14f, bw, 28f);
            c.roundedRect(back, 8f, Argb.withAlpha(p.text(), 0.05f + 0.05f * hover));
            PanelPaint.chevronLeft(c, back.x() + 13f, cy, 8f, 1.6f, p.textSecondary());
            PanelPaint.text(c, MEDIUM, 12.5f, AetherLang.localize("Back"), back.x() + 22f, cy, p.textSecondary());
            f.hits().add(id, back, HitHandler.click(style::up), Cursor.HAND);
            x = back.right() + 14f;
            PanelPaint.icon(c, page.icon(), x + 13f, cy, 26f, 0xFFFFFFFF);
            x += 36f;
            PanelPaint.fitText(c, SEMIBOLD, 16f, page.name(), x, cy, header.right() - x - 90f, p.text());
            return;
        }
        PanelPaint.icon(c, category.icon(), x + 16f, cy, 30f, 0xFFFFFFFF);
        x += 44f;
        float nameW = c.textWidth(SEMIBOLD, 17f, category.name());
        PanelPaint.text(c, SEMIBOLD, 17f, category.name(), x, cy, p.text());
        float sep = x + nameW + 12f;
        c.line(sep, cy - 9f, sep, cy + 9f, 1f, PanelPaint.hairline(p));
        String count = category.pages().size() + " " + AetherLang.localize(category.pages().size() == 1 ? "module" : "modules");
        float right = PanelPaint.textRight(c, MEDIUM, 11.5f, count, header.right() - 18f, cy, p.textMuted());
        PanelPaint.fitText(c, REGULAR, 12f, category.description(), sep + 12f, cy, right - sep - 24f, p.textMuted());
    }

    private void drawModules(PanelFrame f, Rect body, PanelNav.Category category, boolean active) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        ScrollState scroll = style.scroll("orbit.cat." + category.id());
        scroll.tick(f.nanos(), 250f, f.frozen());
        if (active) {
            f.hits().add("orbit.cat.scroll." + category.id(), body, new HitHandler() {
                @Override
                public boolean scroll(PointerEvent e, double dy) {
                    scroll.scrollBy((float) (-dy * 48.0));
                    return true;
                }
            });
        }
        float x = body.x() + PAD;
        float w = body.w() - PAD * 2f;
        float origin = body.y() - scroll.offset();
        float y = origin + 12f;
        c.save();
        c.clip(body);
        List<PanelNav.Page> failsafes = category.pages().stream().filter(PanelOrbit::isFailsafe).toList();
        if (active) {
            hoveredFailsafe = null;
            hoveredCard = null;
        }
        if (!failsafes.isEmpty()) {
            y += drawPerimeter(f, failsafes, new Rect(x, y, w, 0f), active) + 10f;
        }
        for (PanelNav.Page page : category.pages()) {
            if (isFailsafe(page)) continue;
            y += drawModuleCard(f, page, new Rect(x, y, w, 0f)) + 10f;
        }
        c.restore();
        scroll.setExtent(y - origin + 6f, body.h());
        PanelModulePage.drawScrollbar(c, p, scroll, body);
    }

    private float drawModuleCard(PanelFrame f, PanelNav.Page page, Rect top) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        List<Setting> essentials = new ArrayList<>();
        int total = essentials(page, essentials);
        boolean on = !page.hasToggle() || page.enabled() || page.runtime();
        float headH = 56f;
        float rowsH = 0f;
        for (Setting setting : essentials) {
            rowsH += style.rows.rowHeight(c, setting, top.w() - 36f);
        }
        int more = total - essentials.size();
        float footH = more > 0 ? 34f : (essentials.isEmpty() ? 0f : 6f);
        float h = headH + rowsH + footH;
        Rect card = new Rect(top.x(), top.y(), top.w(), h);
        if (!c.isVisible(card)) return h;
        // the card rises a little under the cursor, its shadow spreading as it lifts
        boolean over = under(f, card);
        if (over) hoveredCard = page;
        float lift = f.anim().hover("orbit.card." + page.id(), over);
        c.save();
        if (lift > 0.01f) c.shadow(card.offset(0f, 4f), 12f, 14f, PanelPaint.shadow(p, 0.5f * lift));
        c.translate(0f, -3f * lift);
        drawModuleCardBody(f, page, card, essentials, total, on, headH, footH, lift);
        c.restore();
        return h;
    }

    private void drawModuleCardBody(PanelFrame f, PanelNav.Page page, Rect card, List<Setting> essentials, int total,
                                    boolean on, float headH, float footH, float lift) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        int more = total - essentials.size();
        String openId = "orbit.open." + page.id();
        float hover = f.anim().hover(openId, f.hits().hovered(openId));
        c.roundedRect(card, 12f, PanelPaint.cardFill(p));
        c.strokeRect(card, 12f, 1f, Argb.mix(PanelPaint.cardBorder(p), p.accent(), on && page.hasToggle() ? 0.35f : 0f));
        Rect head = new Rect(card.x(), card.y(), card.w(), headH);
        if (hover > 0.01f) c.roundedRect(head, 12f, Argb.withAlpha(p.text(), 0.035f * hover));
        PanelPaint.iconTile(c, p, new Rect(card.x() + 12f, card.y() + 9f, 38f, 38f), page.icon(), 24f + 4f * lift, 9f);
        float tx = card.x() + 62f;
        float right = card.right() - 14f;
        if (page.hasToggle()) {
            String toggleId = "orbit.toggle." + page.id();
            Rect sw = new Rect(right - 20f, card.y() + 18f, 20f, 20f);
            float onT = f.anim().spring(toggleId, page.enabled() ? 1f : 0f);
            drawCheckbox(c, p, sw, onT, f.hits().hovered(toggleId) ? 1f : 0f);
            f.hits().add(toggleId, sw.inset(-6f), HitHandler.click(() -> {
                page.tab().toggle();
                if (page.enabled()) style.juice.burst(p.accent());
            }), Cursor.HAND);
            right = sw.x() - 12f;
        }
        PanelPaint.fitText(c, SEMIBOLD, 14f, page.name(), tx, card.y() + 21f, right - tx, on ? p.text() : p.textSecondary());
        PanelPaint.fitText(c, REGULAR, 11.5f, page.description(), tx, card.y() + 38f, right - tx, p.textMuted());
        f.hits().add(openId, new Rect(head.x(), head.y(), right - head.x(), headH),
                HitHandler.click(() -> style.openPage(page.id())), Cursor.HAND);

        float rowY = card.y() + headH;
        if (!essentials.isEmpty()) {
            rowY += style.rows.drawFlat(f, page.id(), essentials, card.x(), rowY, card.w(), on);
        }
        if (more > 0) {
            String moreId = "orbit.more." + page.id();
            float mh = f.anim().hover(moreId, f.hits().hovered(moreId));
            Rect moreRect = new Rect(card.x(), rowY, card.w(), footH);
            c.line(card.x() + 18f, rowY, card.right() - 18f, rowY, 1f, PanelPaint.hairline(p));
            String label = AetherLang.localize("All settings") + " · " + more + " " + AetherLang.localize("more");
            float lw = c.textWidth(MEDIUM, 11.5f, label);
            int color = Argb.mix(p.textMuted(), p.text(), mh);
            PanelPaint.text(c, MEDIUM, 11.5f, label, card.x() + 18f, moreRect.centerY(), color);
            PanelPaint.chevronRight(c, card.x() + 26f + lw + mh * 2f, moreRect.centerY(), 7f, 1.4f, color);
            f.hits().add(moreId, moreRect, HitHandler.click(() -> style.openPage(page.id())), Cursor.HAND);
        }
    }

    // whether the cursor is over r on this frame's canvas
    private static boolean under(PanelFrame f, Rect r) {
        return !f.frozen() && f.canvas().toRoot(r).contains(f.mouseX(), f.mouseY())
                && f.canvas().rootClip().contains(f.mouseX(), f.mouseY());
    }

    static boolean isFailsafe(PanelNav.Page page) {
        return FAILSAFE_ITEMS.containsKey(page.tab().rawName());
    }

    String hoveredFailsafe() {
        return hoveredFailsafe;
    }

    PanelNav.Page hoveredCard() {
        return hoveredCard;
    }

    // the failsafes as one security perimeter: a tile per failsafe with its item, armed lamp, action and delay
    private float drawPerimeter(PanelFrame f, List<PanelNav.Page> failsafes, Rect top, boolean active) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        int armed = 0;
        for (PanelNav.Page page : failsafes) if (page.enabled()) armed++;
        int columns = 3;
        float gap = 8f;
        float headH = 50f;
        float tileW = (top.w() - 24f - gap * (columns - 1)) / columns;
        float tileH = 62f;
        int rows = (failsafes.size() + columns - 1) / columns;
        float h = headH + rows * tileH + (rows - 1) * gap + 14f;
        Rect card = new Rect(top.x(), top.y(), top.w(), h);
        if (!c.isVisible(card)) return h;
        c.roundedRect(card, 12f, PanelPaint.cardFill(p));
        c.strokeRect(card, 12f, 1f, Argb.mix(PanelPaint.cardBorder(p), p.success(), armed == failsafes.size() ? 0.35f : 0f));
        PanelPaint.iconTile(c, p, new Rect(card.x() + 12f, card.y() + 8f, 34f, 34f), dev.aether.ui.gui.Icon.item("totem_of_undying"), 22f, 9f);
        PanelPaint.text(c, SEMIBOLD, 14f, AetherLang.localize("Failsafes"), card.x() + 56f, card.y() + 19f, p.text());
        String status = armed + " " + AetherLang.localize("of") + " " + failsafes.size() + " " + AetherLang.localize("armed");
        PanelPaint.text(c, REGULAR, 11.5f, status, card.x() + 56f, card.y() + 35f, p.textMuted());
        float tx0 = card.x() + 12f;
        float ty = card.y() + headH;
        for (int i = 0; i < failsafes.size(); i++) {
            PanelNav.Page page = failsafes.get(i);
            float tx = tx0 + (i % columns) * (tileW + gap);
            float tyy = ty + (i / columns) * (tileH + gap);
            Rect tile = new Rect(tx, tyy, tileW, tileH);
            drawFailsafeTile(f, page, tile, active);
        }
        return h;
    }

    private void drawFailsafeTile(PanelFrame f, PanelNav.Page page, Rect tile, boolean active) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        boolean on = page.enabled();
        String id = "orbit.failsafe." + page.id();
        String lampId = id + ".lamp";
        boolean hover = f.hits().hovered(id) || f.hits().hovered(lampId);
        if (hover && active) hoveredFailsafe = page.tab().rawName();
        float h = f.anim().hover(id, hover);
        c.save();
        if (h > 0.01f) c.shadow(tile.offset(0f, 3f), 10f, 10f, PanelPaint.shadow(p, 0.45f * h));
        c.translate(0f, -2f * h);
        c.roundedRect(tile, 10f, Argb.mix(PanelPaint.windowFill(p), p.text(), 0.03f + 0.04f * h));
        c.strokeRect(tile, 10f, 1f, Argb.mix(PanelPaint.hairline(p), on ? p.success() : p.border(), on ? 0.45f : 0.2f));
        String item = FAILSAFE_ITEMS.get(page.tab().rawName());
        c.save();
        if (!on) c.alpha(0.5f);
        PanelPaint.icon(c, dev.aether.ui.gui.Icon.item(item), tile.x() + 20f, tile.y() + 19f, 30f + 4f * h, 0xFFFFFFFF);
        c.restore();
        float textX = tile.x() + 40f;
        float lampX = tile.right() - 13f;
        PanelPaint.fitText(c, SEMIBOLD, 11.5f, page.name(), textX, tile.y() + 16f, lampX - textX - 10f, on ? p.text() : p.textSecondary());
        PanelPaint.fitText(c, REGULAR, 10f, failsafeSummary(page), tile.x() + 10f, tile.y() + 44f, tile.w() - 20f, p.textMuted());
        float glow = f.anim().spring(lampId, on ? 1f : 0f);
        if (glow > 0.01f) c.circle(lampX, tile.y() + 16f, 7f, Argb.withAlpha(p.success(), 0.22f * glow));
        c.circle(lampX, tile.y() + 16f, 4f, Argb.mix(Argb.mix(p.border(), p.text(), 0.15f), p.success(), glow));
        if (active) {
            f.hits().add(lampId, new Rect(lampX - 11f, tile.y() + 5f, 22f, 22f), HitHandler.click(page.tab()::toggle), Cursor.HAND);
            f.hits().add(id, tile, HitHandler.click(() -> style.openPage(page.id())), Cursor.HAND);
        }
        c.restore();
    }

    // "Stop · 1.5s" from the failsafe's own Action and Trigger Delay settings
    private static String failsafeSummary(PanelNav.Page page) {
        String action = null;
        String delay = null;
        for (SettingGroup group : page.tab().groups()) {
            for (Setting setting : group.getSettings()) {
                if (action == null && setting instanceof dev.aether.ui.settings.DropdownSetting dropdown
                        && setting.getRawName().equalsIgnoreCase("Action")) {
                    action = dropdown.getSelectedOption();
                }
                if (delay == null && setting.getRawName().toLowerCase(java.util.Locale.ROOT).contains("delay")) {
                    if (setting instanceof dev.aether.ui.settings.SliderSetting slider) {
                        delay = PanelRows.formatValue(slider.getValue(), slider.getDecimals(), slider.getSuffix());
                    } else if (setting instanceof dev.aether.ui.settings.RangeSliderSetting range) {
                        delay = PanelRows.formatValue(range.getLowerValue(), range.getDecimals(), "") + "–"
                                + PanelRows.formatValue(range.getUpperValue(), range.getDecimals(), range.getSuffix());
                    }
                }
            }
        }
        if (action == null && delay == null) return page.description();
        if (action == null) return delay;
        return delay == null ? action : action + " · " + delay;
    }

    // the first few quick settings of a page, in order; returns how many visible settings it has in total
    private static int essentials(PanelNav.Page page, List<Setting> out) {
        int total = 0;
        for (SettingGroup group : page.tab().groups()) {
            if (!group.isAlwaysOn() && !group.isEnabled()) continue;
            for (Setting setting : group.getSettings()) {
                if (!setting.isVisible() || setting.getType() == SettingType.SECTION) continue;
                total++;
                if (out.size() < ESSENTIALS && quick(setting.getType())) out.add(setting);
            }
        }
        return total;
    }

    private static boolean quick(SettingType type) {
        return switch (type) {
            case TOGGLE, SLIDER, RANGE_SLIDER, DROPDOWN, PLOT, COLOR -> true;
            default -> false;
        };
    }

    private void drawOverview(PanelFrame f, Rect area, PanelNav.Category category) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        float x = area.x() + 34f;
        float y = area.y() + 40f;
        PanelPaint.icon(c, category.icon(), x + 48f, y + 48f, 96f, 0xFFFFFFFF);
        float tx = x + 122f;
        PanelPaint.fitText(c, BOLD, 40f, category.name(), tx, y + 34f, area.right() - tx - 24f, p.text());
        int toggleable = category.toggleableCount();
        String detail = toggleable > 0
                ? category.enabledCount() + " " + AetherLang.localize("of") + " " + toggleable + " " + AetherLang.localize("on")
                : category.pages().size() + " " + AetherLang.localize(category.pages().size() == 1 ? "module" : "modules");
        if (toggleable > 0 && category.enabledCount() > 0) {
            c.circle(tx + 6f, y + 76f, 6f, p.success());
            PanelPaint.text(c, MEDIUM, 22f, detail, tx + 20f, y + 76f, p.textSecondary());
        } else {
            PanelPaint.text(c, MEDIUM, 22f, detail, tx, y + 76f, p.textSecondary());
        }
        float iy = y + 140f;
        float ix = x;
        float size = 70f;
        for (PanelNav.Page page : category.pages()) {
            if (ix + size > area.right() - 24f) {
                ix = x;
                iy += size + 14f;
            }
            if (iy + size > area.bottom() - 20f) break;
            Rect tile = new Rect(ix, iy, size, size);
            boolean on = page.hasToggle() && page.enabled();
            c.roundedRect(tile, 14f, PanelPaint.cardFill(p));
            if (on) c.strokeRect(tile, 14f, 2f, p.accent());
            PanelPaint.icon(c, page.icon(), tile.centerX(), tile.centerY(), 44f, 0xFFFFFFFF);
            ix += size + 14f;
        }
    }

    // the current menu's checkbox toggle, kept on purpose
    static void drawCheckbox(GuiCanvas c, Palette p, Rect r, float on, float hover) {
        int base = PanelPaint.fieldFill(p);
        c.roundedRect(r, 5f, Argb.mix(base, p.accent(), on));
        c.strokeRect(r, 5f, 1f, Argb.mix(Argb.mix(p.border(), p.text(), hover * 0.4f), p.accent(), on));
        if (on > 0.05f) {
            c.save();
            c.alpha(Math.min(1f, on));
            PanelPaint.check(c, r.centerX(), r.centerY() + 0.5f, r.w() * 0.36f, 2f, p.onAccent());
            c.restore();
        }
    }
}
