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

// the two-level sidebar: categories with enabled badges, the open category's pages with status dots, and a
// footer of quick actions, the profile switcher and the account card; collapses to an icon rail
final class PanelSidebar {
    static final Icon LOGO = Icon.svg("/assets/aether/icons/logo.svg");
    static final Icon SEARCH = Icon.svg("/assets/aether/icons/search.svg");
    static final Icon PLAY = Icon.svg("/assets/aether/icons/play.svg");
    static final Icon HUD = Icon.svg("/assets/aether/icons/hud.svg");
    static final Icon COLORS = Icon.svg("/assets/aether/icons/colors.svg");
    static final Icon CONFIG = Icon.svg("/assets/aether/icons/config.svg");

    private static final float TOP_H = 60f;
    private static final float CATEGORY_H = 38f;
    private static final float PAGE_H = 30f;
    private static final float FOOTER_H = 180f;
    private static final float RAIL_FOOTER_H = 150f;
    private static final long PROFILE_REFRESH_NANOS = 2_000_000_000L;

    private final PanelStyle style;
    private List<String> profiles = List.of();
    private long profilesRead = Long.MIN_VALUE;
    private float dragStartX;
    private float dragStartY;

    PanelSidebar(PanelStyle style) {
        this.style = style;
    }

    void draw(PanelFrame f, Rect side) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        c.save();
        c.clip(side);
        c.roundedRect(new Rect(side.x(), side.y(), side.w() + PanelStyle.WINDOW_RADIUS, side.h()),
                PanelStyle.WINDOW_RADIUS, PanelPaint.sidebarFill(p));
        c.line(side.right() - 0.5f, side.y(), side.right() - 0.5f, side.bottom(), 1f, PanelPaint.hairline(p));
        if (style.rail()) {
            drawRail(f, side);
        } else {
            drawExpanded(f, side);
        }
        c.restore();
    }

    // -- expanded -----------------------------------------------------------------

    private void drawExpanded(PanelFrame f, Rect side) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        Rect top = new Rect(side.x(), side.y(), side.w(), TOP_H);
        dragHandle(f, top);
        PanelPaint.icon(c, LOGO, side.x() + 30f, top.centerY(), 22f, p.accent());
        PanelPaint.text(c, BOLD, 17f, "Aether", side.x() + 48f, top.centerY(), p.text());
        Rect collapse = new Rect(side.right() - 42f, top.centerY() - 14f, 28f, 28f);
        boolean hoverCollapse = f.hits().hovered("aurora.sb.collapse");
        if (hoverCollapse) {
            c.roundedRect(collapse, 8f, p.hover());
        }
        PanelPaint.sidebarGlyph(c, collapse.centerX(), collapse.centerY(), 15f, 1.4f,
                hoverCollapse ? p.text() : p.textMuted());
        f.hits().add("aurora.sb.collapse", collapse, HitHandler.click(style::toggleRail), Cursor.HAND);

        Rect searchPill = new Rect(side.x() + 14f, side.y() + TOP_H + 2f, side.w() - 28f, 34f);
        boolean hoverSearch = f.hits().hovered("aurora.sb.search");
        c.roundedRect(searchPill, 10f, Argb.withAlpha(p.text(), hoverSearch ? 0.09f : 0.055f));
        c.strokeRect(searchPill, 10f, 1f, Argb.withAlpha(p.border(), hoverSearch ? 0.45f : 0.25f));
        PanelPaint.icon(c, SEARCH, searchPill.x() + 18f, searchPill.centerY(), 14f, p.textMuted());
        PanelPaint.text(c, REGULAR, 12.5f, AetherLang.localize("Search"), searchPill.x() + 33f,
                searchPill.centerY(), p.textMuted());
        float capW = c.textWidth(MEDIUM, 10.5f, "Ctrl+F") + 12f;
        PanelPaint.keycap(c, p, searchPill.right() - capW - 8f, searchPill.centerY(), "Ctrl+F");
        f.hits().add("aurora.sb.search", searchPill, HitHandler.click(() -> style.openSearch("")), Cursor.HAND);

        float listTop = searchPill.bottom() + 12f;
        Rect list = new Rect(side.x(), listTop, side.w(), side.bottom() - FOOTER_H - listTop);
        drawCategoryList(f, list);
        drawFooter(f, new Rect(side.x(), side.bottom() - FOOTER_H, side.w(), FOOTER_H));
    }

    private void drawCategoryList(PanelFrame f, Rect list) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        ScrollState scroll = style.scroll("aurora.sidebar");
        scroll.tick(f.nanos(), 250f, f.frozen());
        f.hits().add("aurora.sb.list", list, new HitHandler() {
            @Override
            public boolean scroll(PointerEvent e, double dy) {
                scroll.scrollBy((float) (-dy * 36.0));
                return true;
            }
        });
        PanelStyle.Location location = style.location();
        c.save();
        c.clip(list);
        float y = list.y() - scroll.offset();
        float rowX = list.x() + 10f;
        float rowW = list.w() - 20f;
        float selectedY = Float.NaN;
        for (PanelNav.Category category : style.nav.categories()) {
            boolean selected = category.id().equals(location.categoryId());
            Rect row = new Rect(rowX, y, rowW, CATEGORY_H);
            String id = "aurora.sb.cat." + category.id();
            boolean hover = f.hits().hovered(id);
            float hoverT = f.anim().hover(id, hover);
            if (selected) {
                selectedY = y;
            } else if (hoverT > 0.01f) {
                c.roundedRect(row, 10f, Argb.multiplyAlpha(p.hover(), hoverT * 1.4f));
            }
            if (selected) {
                float sy = f.anim().spring("aurora.sb.sel", y - list.y() + scroll.offset());
                Rect pill = new Rect(rowX, list.y() - scroll.offset() + sy, rowW, CATEGORY_H);
                c.roundedRect(pill, 10f, PanelPaint.accentWash(p, 1f));
                c.roundedRect(new Rect(pill.x(), pill.y() + 10f, 3f, pill.h() - 20f), 1.5f, p.accent());
            }
            PanelPaint.icon(c, category.icon(), row.x() + 18f, row.centerY(), 20f, p.text());
            int text = selected ? p.text() : Argb.mix(p.textSecondary(), p.text(), hoverT);
            float badgeW = 0f;
            int toggleable = category.toggleableCount();
            if (toggleable > 0) {
                String badge = Integer.toString(category.enabledCount());
                float bw = Math.max(22f, PanelPaint.pillWidth(c, badge, SEMIBOLD, 10.5f, 7f));
                Rect b = new Rect(row.right() - bw - 8f, row.centerY() - 9f, bw, 18f);
                boolean any = category.enabledCount() > 0;
                c.roundedRect(b, 9f, any ? Argb.withAlpha(p.accent(), selected ? 0.30f : 0.18f)
                        : Argb.withAlpha(p.text(), 0.06f));
                PanelPaint.textCentered(c, SEMIBOLD, 10.5f, badge, b.centerX(), b.centerY(),
                        any ? Argb.mix(p.accent(), p.text(), 0.35f) : p.textMuted());
                badgeW = bw + 8f;
            }
            PanelPaint.fitText(c, selected ? SEMIBOLD : MEDIUM, 13f, category.name(), row.x() + 38f, row.centerY(),
                    row.w() - 46f - badgeW, text);
            f.hits().add(id, row, HitHandler.click(() -> {
                if (selected && location.pageId() == null) {
                    style.goHome();
                } else {
                    style.openCategory(category.id());
                }
            }), Cursor.HAND);
            y += CATEGORY_H + 2f;
            if (selected) {
                y = drawPages(f, category, rowX, y, rowW) + 6f;
            }
        }
        if (Float.isNaN(selectedY)) {
            f.anim().spring("aurora.sb.sel", 0f);
        }
        c.restore();
        float content = y + scroll.offset() - list.y() + 8f;
        scroll.setExtent(content, list.h());
        if (scroll.max() > 0f) {
            Rect thumb = scroll.thumb(new Rect(list.right() - 5f, list.y() + 4f, 3f, list.h() - 8f), 24f);
            c.roundedRect(thumb, 1.5f, Argb.withAlpha(p.text(), 0.18f));
            float fade = Math.min(1f, scroll.max() - scroll.offset());
            if (fade > 0f) {
                c.verticalGradient(new Rect(list.x(), list.bottom() - 18f, list.w(), 18f), 0f, 0,
                        Argb.withAlpha(Argb.mix(p.sidebar(), p.panel(), 0.5f), 0.55f * fade));
            }
        }
    }

    private float drawPages(PanelFrame f, PanelNav.Category category, float x, float y, float w) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        PanelStyle.Location location = style.location();
        float guideTop = y;
        List<PanelNav.Page> pages = category.pages();
        if (pages.isEmpty()) {
            PanelPaint.text(c, REGULAR, 12f, AetherLang.localize("Nothing here yet"), x + 38f, y + PAGE_H / 2f,
                    p.textMuted());
            return y + PAGE_H;
        }
        int index = 0;
        for (PanelNav.Page page : pages) {
            float enter = f.anim().stagger("aurora.sb.pages." + category.id(), index++);
            boolean selected = page.id().equals(location.pageId());
            Rect row = new Rect(x + 26f, y, w - 26f, PAGE_H);
            String id = "aurora.sb.page." + page.id();
            boolean hover = f.hits().hovered(id);
            float hoverT = f.anim().hover(id, hover);
            c.save();
            c.alpha(enter);
            c.translate((1f - enter) * -6f, 0f);
            if (selected) {
                c.roundedRect(row, 8f, Argb.withAlpha(p.text(), 0.08f));
            } else if (hoverT > 0.01f) {
                c.roundedRect(row, 8f, Argb.multiplyAlpha(p.hover(), hoverT));
            }
            float textX = row.x() + 12f;
            if (page.hasToggle() && !page.runtime()) {
                PanelPaint.statusDot(c, p, row.x() + 13f, row.centerY(), page.enabled());
                textX = row.x() + 25f;
            }
            int color = selected ? p.text() : Argb.mix(p.textSecondary(), p.text(), hoverT * 0.7f);
            PanelPaint.fitText(c, selected ? MEDIUM : REGULAR, 12.5f, page.name(), textX, row.centerY(),
                    row.right() - textX - 8f, color);
            c.restore();
            f.hits().add(id, row, HitHandler.click(() -> style.openPage(page.id())), Cursor.HAND);
            y += PAGE_H;
        }
        c.line(x + 18f, guideTop + 4f, x + 18f, y - 4f, 1f, PanelPaint.hairline(p));
        return y;
    }

    private void drawFooter(PanelFrame f, Rect footer) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        c.line(footer.x() + 14f, footer.y(), footer.right() - 14f, footer.y(), 1f, PanelPaint.hairline(p));
        float x = footer.x() + 14f;
        float w = footer.w() - 28f;
        float y = footer.y() + 12f;

        String[] labels = {AetherLang.localize("Macros"), AetherLang.localize("HUD"), AetherLang.localize("Theme")};
        Icon[] icons = {PLAY, HUD, COLORS};
        Runnable[] actions = {() -> f.host().openMacroMenu(), () -> f.host().openHudEditor(), style::openAppearance};
        float gap = 6f;
        float bw = (w - gap * 2f) / 3f;
        for (int i = 0; i < 3; i++) {
            Rect b = new Rect(x + i * (bw + gap), y, bw, 50f);
            String id = "aurora.sb.quick." + i;
            float hoverT = f.anim().hover(id, f.hits().hovered(id));
            c.roundedRect(b, 10f, Argb.withAlpha(p.text(), 0.045f + hoverT * 0.05f));
            PanelPaint.icon(c, icons[i], b.centerX(), b.y() + 18f, 15f, Argb.mix(p.textSecondary(), p.accent(), hoverT));
            PanelPaint.textCentered(c, MEDIUM, 10.5f, c.ellipsize(MEDIUM, 10.5f, labels[i], bw - 8f), b.centerX(),
                    b.y() + 37f, p.textSecondary());
            f.hits().add(id, b, HitHandler.click(actions[i]), Cursor.HAND);
        }
        y += 58f;

        Rect profile = new Rect(x, y, w, 36f);
        String profileId = "aurora.sb.profile";
        float hoverProfile = f.anim().hover(profileId, f.hits().hovered(profileId));
        c.roundedRect(profile, 10f, Argb.withAlpha(p.text(), 0.03f + hoverProfile * 0.05f));
        c.strokeRect(profile, 10f, 1f, Argb.withAlpha(p.border(), 0.22f));
        PanelPaint.icon(c, CONFIG, profile.x() + 17f, profile.centerY(), 14f, p.textMuted());
        String active = f.host().activeProfile();
        String profileName = active == null || active.isBlank() ? AetherLang.localize("No profile loaded") : active;
        PanelPaint.text(c, REGULAR, 10f, AetherLang.localize("Profile"), profile.x() + 32f, profile.y() + 11f,
                p.textMuted());
        PanelPaint.fitText(c, MEDIUM, 12f, profileName, profile.x() + 32f, profile.y() + 24f, profile.w() - 60f, p.text());
        PanelPaint.chevronDown(c, profile.right() - 16f, profile.centerY(), 7f, 1.4f, p.textMuted());
        f.hits().add(profileId, profile, HitHandler.click(() -> openProfiles(f, profile)), Cursor.HAND);
        y += 44f;

        drawAccount(f, new Rect(x, y, w, 50f));
    }

    private void openProfiles(PanelFrame f, Rect anchor) {
        List<String> names = profiles(f);
        style.overlays.openList("aurora.sb.profile", f.canvas().toRoot(anchor), names,
                names.indexOf(f.host().activeProfile()), AetherLang.localize("No saved profiles yet"),
                index -> f.host().loadProfile(names.get(index)), true);
    }

    private List<String> profiles(PanelFrame f) {
        if (f.nanos() - profilesRead > PROFILE_REFRESH_NANOS || profilesRead == Long.MIN_VALUE) {
            profiles = List.copyOf(f.host().profiles());
            profilesRead = f.nanos();
        }
        return profiles;
    }

    private void drawAccount(PanelFrame f, Rect card) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        PanelHost.Account account = f.host().account();
        c.roundedRect(card, 12f, Argb.withAlpha(p.text(), 0.04f));
        float head = 30f;
        Rect headRect = new Rect(card.x() + 10f, card.centerY() - head / 2f, head, head);
        c.save();
        c.clip(headRect);
        f.host().paintHead(c, headRect.x(), headRect.y(), head);
        c.restore();
        c.strokeRect(headRect, 3f, 1f, Argb.withAlpha(p.text(), 0.12f));
        float tx = headRect.right() + 10f;
        String name = f.host().streamerMode() ? AetherLang.localize("Hidden") : account.name();
        PanelPaint.fitText(c, SEMIBOLD, 12.5f, name, tx, card.centerY() - 7f, card.right() - tx - 8f, p.text());
        switch (account.state()) {
            case SIGNED_IN -> {
                long minutes = account.totalSeconds() / 60L;
                String hours = (minutes / 60L) + "h " + (minutes % 60L) + "m " + AetherLang.localize("played");
                PanelPaint.text(c, REGULAR, 11f, hours, tx, card.centerY() + 8f, p.textMuted());
            }
            case SIGNING_IN -> PanelPaint.text(c, MEDIUM, 11f, AetherLang.localize("Signing in…"), tx,
                    card.centerY() + 8f, p.textMuted());
            case SIGNED_OUT -> {
                String label = AetherLang.localize("Log in");
                float lw = c.textWidth(SEMIBOLD, 11f, label) + 18f;
                Rect login = new Rect(tx, card.centerY() + 1f, lw, 18f);
                boolean hover = f.hits().hovered("aurora.sb.login");
                c.roundedRect(login, 9f, Argb.withAlpha(p.accent(), hover ? 0.32f : 0.20f));
                PanelPaint.text(c, SEMIBOLD, 11f, label, login.x() + 9f, login.centerY(), Argb.mix(p.accent(), p.text(), 0.3f));
                f.hits().add("aurora.sb.login", login, HitHandler.click(() -> f.host().beginLogin()), Cursor.HAND);
            }
        }
    }

    // -- rail -----------------------------------------------------------------------

    private void drawRail(PanelFrame f, Rect side) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        Rect top = new Rect(side.x(), side.y(), side.w(), TOP_H);
        dragHandle(f, top);
        Rect expand = new Rect(side.centerX() - 18f, top.centerY() - 18f, 36f, 36f);
        boolean hoverExpand = f.hits().hovered("aurora.sb.expand");
        if (hoverExpand) {
            c.roundedRect(expand, 10f, p.hover());
            PanelPaint.sidebarGlyph(c, expand.centerX(), expand.centerY(), 15f, 1.4f, p.text());
        } else {
            PanelPaint.icon(c, LOGO, expand.centerX(), expand.centerY(), 22f, p.accent());
        }
        f.hits().add("aurora.sb.expand", expand, HitHandler.click(style::toggleRail), Cursor.HAND);

        Rect searchButton = new Rect(side.centerX() - 20f, side.y() + TOP_H + 2f, 40f, 34f);
        boolean hoverSearch = f.hits().hovered("aurora.sb.search");
        c.roundedRect(searchButton, 10f, Argb.withAlpha(p.text(), hoverSearch ? 0.09f : 0.055f));
        PanelPaint.icon(c, SEARCH, searchButton.centerX(), searchButton.centerY(), 15f, p.textMuted());
        f.hits().add("aurora.sb.search", searchButton, HitHandler.click(() -> style.openSearch("")), Cursor.HAND);

        float listTop = searchButton.bottom() + 10f;
        Rect list = new Rect(side.x(), listTop, side.w(), side.bottom() - RAIL_FOOTER_H - listTop);
        ScrollState scroll = style.scroll("aurora.rail");
        scroll.tick(f.nanos(), 250f, f.frozen());
        f.hits().add("aurora.sb.rail", list, new HitHandler() {
            @Override
            public boolean scroll(PointerEvent e, double dy) {
                scroll.scrollBy((float) (-dy * 44.0));
                return true;
            }
        });
        c.save();
        c.clip(list);
        float y = list.y() + 2f - scroll.offset();
        PanelStyle.Location location = style.location();
        for (PanelNav.Category category : style.nav.categories()) {
            Rect cell = new Rect(side.centerX() - 20f, y, 40f, 40f);
            String id = "aurora.sb.cat." + category.id();
            boolean selected = category.id().equals(location.categoryId());
            float hoverT = f.anim().hover(id, f.hits().hovered(id));
            if (selected) {
                c.roundedRect(cell, 11f, PanelPaint.accentWash(p, 1.2f));
                c.roundedRect(new Rect(side.x() + 3f, cell.y() + 11f, 3f, cell.h() - 22f), 1.5f, p.accent());
            } else if (hoverT > 0.01f) {
                c.roundedRect(cell, 11f, Argb.multiplyAlpha(p.hover(), hoverT * 1.4f));
            }
            PanelPaint.icon(c, category.icon(), cell.centerX(), cell.centerY(), 22f, p.text());
            if (category.enabledCount() > 0) {
                c.circle(cell.right() - 6f, cell.y() + 7f, 3.5f, p.accent());
            }
            f.hits().add(id, cell, HitHandler.click(() -> style.openCategory(category.id())), Cursor.HAND);
            y += 44f;
        }
        c.restore();
        scroll.setExtent(y + scroll.offset() - list.y() + 2f, list.h());

        float fy = side.bottom() - RAIL_FOOTER_H + 10f;
        c.line(side.x() + 14f, fy - 6f, side.right() - 14f, fy - 6f, 1f, PanelPaint.hairline(p));
        Icon[] icons = {PLAY, HUD, COLORS};
        Runnable[] actions = {() -> f.host().openMacroMenu(), () -> f.host().openHudEditor(), style::openAppearance};
        for (int i = 0; i < 3; i++) {
            Rect b = new Rect(side.centerX() - 18f, fy, 36f, 32f);
            String id = "aurora.sb.quick." + i;
            float hoverT = f.anim().hover(id, f.hits().hovered(id));
            c.roundedRect(b, 9f, Argb.withAlpha(p.text(), hoverT * 0.08f));
            PanelPaint.icon(c, icons[i], b.centerX(), b.centerY(), 15f, Argb.mix(p.textMuted(), p.accent(), hoverT));
            f.hits().add(id, b, HitHandler.click(actions[i]), Cursor.HAND);
            fy += 33f;
        }
        Rect head = new Rect(side.centerX() - 15f, fy + 6f, 30f, 30f);
        c.save();
        c.clip(head);
        f.host().paintHead(c, head.x(), head.y(), head.w());
        c.restore();
    }

    private void dragHandle(PanelFrame f, Rect area) {
        f.hits().add("aurora.drag", area, new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                if (e.button() != 0) {
                    return false;
                }
                dragStartX = e.rootX() - style.dragX();
                dragStartY = e.rootY() - style.dragY();
                return true;
            }

            @Override
            public void drag(PointerEvent e) {
                style.dragWindow(e.rootX() - dragStartX, e.rootY() - dragStartY);
            }
        }, Cursor.MOVE);
    }

    void dragArea(PanelFrame f, Rect area) {
        dragHandle(f, area);
    }
}
