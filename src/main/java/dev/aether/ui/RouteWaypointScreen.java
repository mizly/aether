package dev.aether.ui;

import dev.aether.modules.routes.Route;
import dev.aether.modules.routes.RouteEditor;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import net.minecraft.core.BlockPos;

// opened by a right click in the route editor: set the island warp and pick how this leg is travelled
public final class RouteWaypointScreen extends CanvasPanelScreen {
    private static final float PANEL_W = 340f;
    private static final float LABEL_H = 20f;
    private static final float OPTION_H = 66f;
    private static final float OPTION_GAP = 10f;

    private final Route route;
    private final BlockPos standing;
    private final Route.LegType existing;
    private final Field warp;

    public RouteWaypointScreen(Route route, BlockPos standing, Route.LegType existing) {
        super("Add Waypoint");
        this.route = route;
        this.standing = standing;
        this.existing = existing;
        this.warp = new Field(route.warp(), 32);
    }

    @Override
    Field focusedField() {
        return warp.focused ? warp : null;
    }

    @Override
    float panelWidth() {
        return PANEL_W;
    }

    @Override
    float panelHeight(float canvasH) {
        return HEADER_H + 1f + PAD + LABEL_H + FIELD_H + PAD + OPTION_H + 14f + 14f + PAD;
    }

    @Override
    void renderPanel(NVGRenderer nvg, float px, float py, float pw, float ph, float mx, float my) {
        String title = AetherLang.localize(existing == null ? "Add Waypoint" : "Change Waypoint");
        renderHeader(nvg, px, py, pw, title, null);
        String coords = standing.getX() + ", " + standing.getY() + ", " + standing.getZ();
        nvg.textRight(Fonts.MONO, coords, px, py + (HEADER_H - 11f) / 2f, pw - PAD, 11f, Theme.TEXT_MUTED);

        float innerW = pw - PAD * 2f;
        float y = py + HEADER_H + 1f + PAD;
        nvg.text(Fonts.REGULAR, AetherLang.localize("Rewarp"), px + PAD, y, 11f, Theme.TEXT_MUTED);
        nvg.textRight(Fonts.REGULAR, AetherLang.localize("Used for every leg of this route"), px + PAD, y, innerW,
                10f, Theme.withAlpha(Theme.TEXT_MUTED, 0.7f));
        y += LABEL_H;
        renderField(nvg, warp, px + PAD, y, innerW, "/warp ", Route.DEFAULT_WARP);
        y += FIELD_H + PAD;

        float optionW = (innerW - OPTION_GAP) / 2f;
        renderOption(nvg, px + PAD, y, optionW, Route.LegType.WALK, "Pathfind here on foot", mx, my);
        renderOption(nvg, px + PAD + optionW + OPTION_GAP, y, optionW, Route.LegType.ETHERWARP,
                "Etherwarp here from the walk's end", mx, my);
        y += OPTION_H + 14f;

        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Esc to cancel"), px, y, pw, 14f, 10f,
                Theme.TEXT_MUTED);
    }

    private void renderOption(NVGRenderer nvg, float x, float y, float w, Route.LegType type, String description,
                              float mx, float my) {
        int color = type == Route.LegType.ETHERWARP ? RouteEditor.ETHERWARP_COLOR : RouteEditor.WALK_COLOR;
        boolean hover = hovered(mx, my, x, y, w, OPTION_H);
        boolean current = existing == type;

        nvg.roundedRect(x, y, w, OPTION_H, 8f, Theme.CARD_BG);
        if (hover || current) {
            nvg.roundedRect(x, y, w, OPTION_H, 8f, Theme.withAlpha(color, hover ? 0.14f : 0.08f));
        }
        nvg.rectOutlineSolid(x, y, w, OPTION_H, 8f, 1f,
                hover || current ? Theme.withAlpha(color, 0.8f) : Theme.SEPARATOR);

        nvg.circle(x + 18f, y + 23f, 4.5f, color);
        nvg.text(Fonts.BOLD, RouteEditor.legLabel(type), x + 30f, y + 16f, 13f, Theme.TEXT_PRIMARY);
        nvg.text(Fonts.REGULAR, fit(nvg, AetherLang.localize(description), Fonts.REGULAR, 10f, w - 28f),
                x + 14f, y + 40f, 10f, Theme.TEXT_SECONDARY);
        addHit(x, y, w, OPTION_H, () -> choose(type));
    }

    private void choose(Route.LegType type) {
        route.setWarp(warp.value());
        RouteEditor.addOrReplace(new Route.Waypoint(standing.getX(), standing.getY(), standing.getZ(), type));
        super.onClose();
    }

    @Override
    public void onClose() {
        // leaving without a pick still keeps an edited warp, it belongs to the route rather than this leg
        if (!warp.value().equals(route.warp())) {
            route.setWarp(warp.value());
            RouteEditor.save();
        }
        super.onClose();
    }
}
