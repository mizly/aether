package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.config.entries.StringEntry;
import dev.aether.modules.routes.Route;
import dev.aether.modules.routes.RouteEditor;
import dev.aether.modules.routes.RouteStore;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// every saved route, one tab per macro folder, laid out like the macro menu
public final class RoutesScreen extends CanvasPanelScreen {
    private static final float PANEL_W = 420f;
    private static final float TAB_H = 34f;
    private static final float CARD_H = 64f;
    private static final float CARD_GAP = 8f;
    private static final float ICON = 16f;
    private static final float ICON_STEP = 30f;
    private static final float LIST_TOP_GAP = 12f;

    private final RouteStore store = RouteStore.config();
    private RouteStore.Folder folder;
    private List<Route> routes = new ArrayList<>();
    private Route renaming;
    private final Field renameField = new Field("", 32);
    private Route armedDelete;
    private Route armedBeforeClick;
    private float scrollY;
    private float maxScrollY;

    public RoutesScreen(RouteStore.Folder folder) {
        super("Routes");
        this.folder = folder == null ? RouteStore.STRIDER_FISHING : folder;
        reload();
    }

    private void reload() {
        routes = store.loadAll(folder);
        renaming = null;
        armedDelete = null;
    }

    @Override
    Field focusedField() {
        return renaming != null && renameField.focused ? renameField : null;
    }

    @Override
    void onFieldSubmit(Field field) {
        commitRename();
    }

    @Override
    void onFieldCancel(Field field) {
        renameField.focused = false;
        renaming = null;
    }

    // deleting takes two clicks on the same card, and any other click disarms it
    @Override
    void onClickAnywhere() {
        armedBeforeClick = armedDelete;
        armedDelete = null;
    }

    @Override
    void afterClick() {
        if (renaming != null && !renameField.focused) {
            commitRename();
        }
    }

    @Override
    void onScroll(float amount) {
        scrollY = Math.max(0f, Math.min(maxScrollY, scrollY - amount * 20f));
    }

    @Override
    float panelWidth() {
        return PANEL_W;
    }

    @Override
    float panelHeight(float canvasH) {
        float list = routes.isEmpty() ? 90f : routes.size() * (CARD_H + CARD_GAP) - CARD_GAP;
        float wanted = HEADER_H + 1f + TAB_H + 1f + LIST_TOP_GAP + list + PAD;
        return Math.min(canvasH - 60f, Math.max(220f, wanted));
    }

    @Override
    void renderPanel(NVGRenderer nvg, float px, float py, float pw, float ph, float mx, float my) {
        renderHeader(nvg, px, py, pw, AetherLang.localize("Routes"), AetherLang.localize("Restart routes for macros"));
        renderNewButton(nvg, px, py, pw, mx, my);

        float tabsY = py + HEADER_H + 1f;
        renderFolderTabs(nvg, px, tabsY, pw, mx, my);

        float listTop = tabsY + TAB_H + 1f + LIST_TOP_GAP;
        float listH = py + ph - listTop - PAD;
        renderList(nvg, px + PAD, listTop, pw - PAD * 2f, listH, mx, my);
    }

    private void renderNewButton(NVGRenderer nvg, float px, float py, float pw, float mx, float my) {
        String label = AetherLang.localize("New Route");
        float iconSize = 12f;
        float w = nvg.textWidth(Fonts.BOLD, label, 11f) + iconSize + 30f;
        float h = 26f;
        float x = px + pw - PAD - w;
        float y = py + (HEADER_H - h) / 2f;
        boolean hover = hovered(mx, my, x, y, w, h);

        nvg.roundedRect(x, y, w, h, 6f, hover ? Theme.ACTION_BTN_HOVER : Theme.ACTION_BTN_BG);
        nvg.rectOutlineSolid(x, y, w, h, 6f, 1f, Theme.withAlpha(Theme.ACCENT_PRIMARY, hover ? 0.9f : 0.55f));
        nvg.renderSVG("/assets/aether/icons/plus.svg", x + 11f, y + (h - iconSize) / 2f, iconSize, iconSize,
                Theme.ACCENT_PRIMARY);
        nvg.text(Fonts.BOLD, label, x + 11f + iconSize + 7f, y + (h - 11f) / 2f, 11f, Theme.ACCENT_PRIMARY);
        addHit(x, y, w, h, this::createRoute);
    }

    private void renderFolderTabs(NVGRenderer nvg, float px, float y, float pw, float mx, float my) {
        float tabX = px + PAD;
        for (RouteStore.Folder candidate : RouteStore.FOLDERS) {
            String label = AetherLang.localize(candidate.displayName());
            float labelW = nvg.textWidth(Fonts.REGULAR, label, 12f);
            boolean on = candidate.equals(folder);
            boolean hover = hovered(mx, my, tabX - 4f, y, labelW + 8f, TAB_H);
            int color = on ? Theme.ACCENT_PRIMARY : (hover ? Theme.TEXT_VALUE : Theme.TEXT_MUTED);
            nvg.text(Fonts.REGULAR, label, tabX, y + (TAB_H - 12f) / 2f, 12f, color);
            if (on) {
                nvg.rect(tabX, y + TAB_H - 2f, labelW, 2f, Theme.ACCENT_PRIMARY);
            }
            addHit(tabX - 4f, y, labelW + 8f, TAB_H, () -> {
                folder = candidate;
                scrollY = 0f;
                reload();
            });
            tabX += labelW + 20f;
        }

        String path = "routes/" + folder.id();
        float iconX = px + pw - PAD - ICON;
        float iconY = y + (TAB_H - ICON) / 2f;
        boolean hover = hovered(mx, my, iconX - 7f, iconY - 7f, ICON + 14f, ICON + 14f);
        nvg.renderSVG("/assets/aether/icons/folder.svg", iconX, iconY, ICON, ICON,
                hover ? Theme.TEXT_PRIMARY : Theme.TEXT_MUTED);
        nvg.textRight(Fonts.MONO, path, px, y + (TAB_H - 10f) / 2f, pw - PAD - ICON - 10f, 10f,
                Theme.withAlpha(Theme.TEXT_MUTED, 0.7f));
        addHit(iconX - 7f, iconY - 7f, ICON + 14f, ICON + 14f, this::openFolder);

        nvg.rect(px, y + TAB_H, pw, 1f, Theme.SEPARATOR);
    }

    private void renderList(NVGRenderer nvg, float x, float y, float w, float h, float mx, float my) {
        if (routes.isEmpty()) {
            nvg.textCentered(Fonts.BOLD, AetherLang.localize("No routes yet"), x, y + 20f, w, 16f, 13f,
                    Theme.TEXT_PRIMARY);
            nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Press New Route to record one in the world"),
                    x, y + 42f, w, 14f, 11f, Theme.TEXT_MUTED);
            maxScrollY = 0f;
            return;
        }

        nvg.pushScissor(x, y, w, h);
        float rowY = y - scrollY;
        for (Route route : routes) {
            if (rowY + CARD_H > y && rowY < y + h) {
                renderCard(nvg, route, x, rowY, w, mx, my, y, h);
            }
            rowY += CARD_H + CARD_GAP;
        }
        nvg.popScissor();

        float total = routes.size() * (CARD_H + CARD_GAP) - CARD_GAP;
        maxScrollY = Math.max(0f, total - h);
        scrollY = Math.max(0f, Math.min(maxScrollY, scrollY));
    }

    private void renderCard(NVGRenderer nvg, Route route, float x, float y, float w, float mx, float my,
                            float clipY, float clipH) {
        boolean selected = route.name().equalsIgnoreCase(selectedName());
        boolean hover = hovered(mx, my, x, y, w, CARD_H) && my >= clipY && my <= clipY + clipH;

        nvg.roundedRect(x, y, w, CARD_H, 8f, Theme.CARD_BG);
        if (selected) {
            nvg.roundedRect(x, y, w, CARD_H, 8f, Theme.withAlpha(Theme.ACCENT_ENABLED, 0.12f));
        }
        nvg.rectOutlineSolid(x, y, w, CARD_H, 8f, 1f,
                selected ? Theme.withAlpha(Theme.ACCENT_ENABLED, 0.75f)
                        : (hover ? Theme.BORDER_HOVER : Theme.SEPARATOR));

        float iconLane = ICON_STEP * 3f + 10f;
        float textMaxW = w - 32f - iconLane;
        if (renaming == route) {
            renderField(nvg, renameField, x + 10f, y + 8f, textMaxW + 6f, null, route.name());
        } else {
            nvg.text(Fonts.BOLD, fit(nvg, route.name(), Fonts.BOLD, 13f, textMaxW), x + 16f, y + 15f, 13f,
                    Theme.TEXT_PRIMARY);
            addHit(x + 10f, y + 8f, textMaxW + 6f, 26f, () -> beginRename(route));
        }

        String warp = route.warpCommand().isEmpty() ? AetherLang.localize("No rewarp") : route.warpCommand();
        String detail = warp + "  ·  " + route.waypoints().size() + " " + AetherLang.localize("waypoints");
        nvg.text(Fonts.REGULAR, fit(nvg, detail, Fonts.REGULAR, 10f, textMaxW), x + 16f, y + 41f, 10f,
                Theme.TEXT_SECONDARY);

        float iconY = y + (CARD_H - ICON) / 2f;
        float deleteX = x + w - 16f - ICON;
        float editX = deleteX - ICON_STEP;
        float selectX = editX - ICON_STEP;

        boolean armed = armedDelete == route;
        renderIcon(nvg, "/assets/aether/icons/trash.svg", deleteX, iconY, mx, my, armed,
                armed ? Theme.ACCENT_ERROR : Theme.TEXT_MUTED, Theme.ACCENT_ERROR, () -> deleteRoute(route));
        renderIcon(nvg, "/assets/aether/icons/pencil.svg", editX, iconY, mx, my, false,
                Theme.TEXT_MUTED, Theme.TEXT_PRIMARY, () -> RouteEditor.begin(Minecraft.getInstance(), folder, route));
        renderIcon(nvg, selected ? "/assets/aether/icons/check_circle.svg" : "/assets/aether/icons/circle.svg",
                selectX, iconY, mx, my, false,
                selected ? Theme.ACCENT_ENABLED : Theme.TEXT_MUTED, Theme.ACCENT_ENABLED, () -> toggleSelected(route));

        if (armed) {
            nvg.textRight(Fonts.REGULAR, AetherLang.localize("Click again to delete"), x, y + 44f, w - 16f, 9f,
                    Theme.ACCENT_ERROR);
        }
    }

    private void renderIcon(NVGRenderer nvg, String icon, float x, float y, float mx, float my, boolean pinned,
                            int color, int hoverColor, Runnable action) {
        float pad = 6f;
        boolean hover = hovered(mx, my, x - pad, y - pad, ICON + pad * 2f, ICON + pad * 2f);
        if (hover || pinned) {
            nvg.roundedRect(x - pad, y - pad, ICON + pad * 2f, ICON + pad * 2f, 6f,
                    Theme.withAlpha(hover ? hoverColor : color, 0.14f));
        }
        nvg.renderSVG(icon, x, y, ICON, ICON, hover ? hoverColor : color);
        addHit(x - pad, y - pad, ICON + pad * 2f, ICON + pad * 2f, action);
    }

    private void createRoute() {
        Route route = new Route(store.nextFreeName(folder), Route.DEFAULT_WARP);
        store.save(folder, route);
        StringEntry selection = selectionEntry(folder);
        if (selection != null && selectedName().isBlank()) {
            selection.set(route.name());
            AetherConfig.save();
        }
        RouteEditor.begin(Minecraft.getInstance(), folder, route);
    }

    private void beginRename(Route route) {
        renaming = route;
        renameField.set(route.name());
        renameField.focused = true;
    }

    private void commitRename() {
        Route route = renaming;
        renaming = null;
        renameField.focused = false;
        if (route == null) {
            return;
        }
        String oldName = route.name();
        if (store.rename(folder, route, renameField.value())) {
            StringEntry selection = selectionEntry(folder);
            if (selection != null && oldName.equalsIgnoreCase(selection.get())) {
                selection.set(route.name());
                AetherConfig.save();
            }
            reload();
        }
    }

    private void deleteRoute(Route route) {
        if (armedBeforeClick != route) {
            armedDelete = route;
            return;
        }
        store.delete(folder, route.name());
        StringEntry selection = selectionEntry(folder);
        if (selection != null && route.name().equalsIgnoreCase(selection.get())) {
            selection.set("");
            AetherConfig.save();
        }
        reload();
    }

    private void toggleSelected(Route route) {
        StringEntry selection = selectionEntry(folder);
        if (selection == null) {
            return;
        }
        selection.set(route.name().equalsIgnoreCase(selection.get()) ? "" : route.name());
        AetherConfig.save();
    }

    private String selectedName() {
        StringEntry selection = selectionEntry(folder);
        String name = selection == null ? "" : selection.get();
        return name == null ? "" : name;
    }

    private static StringEntry selectionEntry(RouteStore.Folder folder) {
        return RouteStore.STRIDER_FISHING.equals(folder) ? AetherConfig.STRIDER_FISHING_RESTART_ROUTE : null;
    }

    private void openFolder() {
        Path directory = store.folderPath(folder);
        try {
            Files.createDirectories(directory);
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("explorer.exe", directory.toAbsolutePath().toString()).start();
            } else if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(directory.toFile());
            }
        } catch (IOException | UnsupportedOperationException e) {
            dev.aether.Aether.LOGGER.warn("Could not open routes folder: {}", e.getMessage());
        }
    }

    @Override
    public void onClose() {
        if (renaming != null) {
            commitRename();
        }
        MainGUIRegistry.refresh();
        Minecraft.getInstance().setScreen(new MainGUI(new MainGUI.LaunchTarget(0, folder.displayName(), true)));
    }
}
