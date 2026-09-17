package dev.aether.ui;

import dev.aether.modules.routes.Route;
import dev.aether.modules.routes.RouteEditor;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

// opened by a left click in the route editor: set the island warp and pick how a leg is travelled,
// or for a waypoint that already exists, change its type, move it or delete it
public final class RouteWaypointScreen extends CanvasPanelScreen {
    private static final float PANEL_W = 360f;
    private static final float LABEL_H = 20f;
    private static final float OPTION_H = 66f;
    private static final float OPTION_GAP = 10f;
    private static final float BUTTON_H = 30f;
    private static final float COORD_GAP = 8f;

    private final Route route;
    private final BlockPos standing;
    private final int index;
    private final Field warp;
    private final Field blockX;
    private final Field blockY;
    private final Field blockZ;
    private Route.LegType selectedType;

    public RouteWaypointScreen(Route route, BlockPos standing, int index) {
        super("Add Waypoint");
        this.route = route;
        this.standing = standing;
        this.index = index;
        this.warp = new Field(route.warp(), 32);
        // the fields show the highlighted block, the waypoint itself is the spot stood on above it
        this.blockX = new Field(String.valueOf(standing.getX()), 9, true);
        this.blockY = new Field(String.valueOf(standing.getY() - 1), 9, true);
        this.blockZ = new Field(String.valueOf(standing.getZ()), 9, true);
        this.selectedType = editing() ? route.waypoints().get(index).type() : null;
    }

    private boolean editing() {
        return index >= 0 && index < route.waypoints().size();
    }

    @Override
    Field focusedField() {
        for (Field field : new Field[] {warp, blockX, blockY, blockZ}) {
            if (field.focused) {
                return field;
            }
        }
        return null;
    }

    @Override
    float panelWidth() {
        return PANEL_W;
    }

    @Override
    float panelHeight(float canvasH) {
        float height = HEADER_H + 1f + PAD + LABEL_H + FIELD_H + PAD + OPTION_H + 14f + 14f + PAD;
        if (editing()) {
            height += LABEL_H + FIELD_H + PAD + BUTTON_H + PAD;
        }
        return height;
    }

    @Override
    void renderPanel(NVGRenderer nvg, float px, float py, float pw, float ph, float mx, float my) {
        String title = editing()
                ? AetherLang.localize("Edit Waypoint") + " #" + (index + 1)
                : AetherLang.localize("Add Waypoint");
        renderHeader(nvg, px, py, pw, title, null);
        String coords = standing.getX() + ", " + (standing.getY() - 1) + ", " + standing.getZ();
        nvg.textRight(Fonts.MONO, coords, px, py + (HEADER_H - 11f) / 2f, pw - PAD, 11f, Theme.TEXT_MUTED);

        float innerW = pw - PAD * 2f;
        float x = px + PAD;
        float y = py + HEADER_H + 1f + PAD;
        renderLabel(nvg, x, y, innerW, "Rewarp", "Used for every leg of this route");
        y += LABEL_H;
        renderField(nvg, warp, x, y, innerW, "/warp ", Route.DEFAULT_WARP);
        y += FIELD_H + PAD;

        if (editing()) {
            renderLabel(nvg, x, y, innerW, "Position", "The highlighted block");
            y += LABEL_H;
            float coordW = (innerW - COORD_GAP * 2f) / 3f;
            renderField(nvg, blockX, x, y, coordW, "X ", "0");
            renderField(nvg, blockY, x + coordW + COORD_GAP, y, coordW, "Y ", "0");
            renderField(nvg, blockZ, x + (coordW + COORD_GAP) * 2f, y, coordW, "Z ", "0");
            y += FIELD_H + PAD;
        }

        float optionW = (innerW - OPTION_GAP) / 2f;
        renderOption(nvg, x, y, optionW, Route.LegType.WALK, "Pathfind here on foot", mx, my);
        renderOption(nvg, x + optionW + OPTION_GAP, y, optionW, Route.LegType.ETHERWARP,
                "Etherwarp here from the walk's end", mx, my);
        y += OPTION_H + PAD;

        if (editing()) {
            float buttonW = (innerW - OPTION_GAP * 2f) / 3f;
            renderButton(nvg, AetherLang.localize("Move in World"), x, y, buttonW, BUTTON_H,
                    Theme.TEXT_VALUE, mx, my, this::moveInWorld);
            renderButton(nvg, AetherLang.localize("Delete"), x + buttonW + OPTION_GAP, y, buttonW, BUTTON_H,
                    Theme.ACCENT_ERROR, mx, my, this::delete);
            renderButton(nvg, AetherLang.localize("Save"), x + (buttonW + OPTION_GAP) * 2f, y, buttonW, BUTTON_H,
                    Theme.ACCENT_ENABLED, mx, my, this::save);
            y += BUTTON_H + 14f;
        } else {
            y -= PAD - 14f;
        }

        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Esc to cancel"), px, y, pw, 14f, 10f,
                Theme.TEXT_MUTED);
    }

    private void renderLabel(NVGRenderer nvg, float x, float y, float w, String label, String hint) {
        nvg.text(Fonts.REGULAR, AetherLang.localize(label), x, y, 11f, Theme.TEXT_MUTED);
        nvg.textRight(Fonts.REGULAR, AetherLang.localize(hint), x, y, w, 10f,
                Theme.withAlpha(Theme.TEXT_MUTED, 0.7f));
    }

    private void renderOption(NVGRenderer nvg, float x, float y, float w, Route.LegType type, String description,
                              float mx, float my) {
        int color = type == Route.LegType.ETHERWARP ? RouteEditor.ETHERWARP_COLOR : RouteEditor.WALK_COLOR;
        boolean hover = hovered(mx, my, x, y, w, OPTION_H);
        boolean current = selectedType == type;

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

    // adding goes straight in on a pick, editing only selects so the position can still be changed before saving
    private void choose(Route.LegType type) {
        if (editing()) {
            selectedType = type;
            return;
        }
        route.setWarp(warp.value());
        RouteEditor.addOrReplace(new Route.Waypoint(standing.getX(), standing.getY(), standing.getZ(), type));
        super.onClose();
    }

    private void save() {
        Integer x = parse(blockX);
        Integer y = parse(blockY);
        Integer z = parse(blockZ);
        if (x == null || y == null || z == null) {
            return;
        }
        route.setWarp(warp.value());
        RouteEditor.replace(index, new Route.Waypoint(x, y + 1, z, selectedType));
        super.onClose();
    }

    private void moveInWorld() {
        route.setWarp(warp.value());
        Route.Waypoint current = route.waypoints().get(index);
        RouteEditor.replace(index, new Route.Waypoint(current.x(), current.y(), current.z(), selectedType));
        RouteEditor.beginMove(Minecraft.getInstance(), index);
    }

    private void delete() {
        route.setWarp(warp.value());
        RouteEditor.remove(index);
        super.onClose();
    }

    private static Integer parse(Field field) {
        try {
            return Integer.parseInt(field.value());
        } catch (NumberFormatException e) {
            field.invalid = true;
            return null;
        }
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
