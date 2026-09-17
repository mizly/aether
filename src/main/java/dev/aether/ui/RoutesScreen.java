package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.config.entries.StringEntry;
import dev.aether.modules.routes.Route;
import dev.aether.modules.routes.RouteEditor;
import dev.aether.modules.routes.RouteStore;
import dev.aether.notification.NotificationManager;
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
        float w = nvg.textWidth(Fonts.REGULAR, label, 11.5f) + 24f;
        float x = px + pw - PAD - w;
        float y = py + (HEADER_H - AetherButton.ROW_H) / 2f;
        renderRowButton(nvg, label, x, y, w, AetherButton.Kind.NORMAL, mx, my, this::createRoute);
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
                renderCard(nvg, route, x, rowY, w, mx, my);
            }
            rowY += CARD_H + CARD_GAP;
        }
        nvg.popScissor();

        float total = routes.size() * (CARD_H + CARD_GAP) - CARD_GAP;
        maxScrollY = Math.max(0f, total - h);
        scrollY = Math.max(0f, Math.min(maxScrollY, scrollY));
    }

    private void renderCard(NVGRenderer nvg, Route route, float x, float y, float w, float mx, float my) {
        boolean selected = route.name().equalsIgnoreCase(selectedName());

        nvg.roundedRect(x, y, w, CARD_H, 7f, Theme.CARD_BG);
        nvg.rectOutline(x, y, w, CARD_H, 7f, 1f, Theme.withAlpha(0xFFFFFFFF, 0.06f));

        float buttonsW = AetherButton.ROW_W * 3f + AetherButton.ROW_GAP * 2f;
        float textMaxW = w - 32f - buttonsW - 10f;
        if (renaming == route) {
            renderField(nvg, renameField, x + 10f, y + 8f, textMaxW + 6f, null, route.name());
        } else {
            nvg.text(Fonts.BOLD, fit(nvg, route.name(), Fonts.BOLD, 13f, textMaxW), x + 16f, y + 15f, 13f,
                    Theme.TEXT_PRIMARY);
            addHit(x + 10f, y + 8f, textMaxW + 6f, 26f, () -> beginRename(route));
        }

        String warp = route.warpCommand().isEmpty() ? AetherLang.localize("No rewarp") : route.warpCommand();
        String detail = warp + "  \u00B7  " + route.waypoints().size() + " " + AetherLang.localize("waypoints");
        nvg.text(Fonts.REGULAR, fit(nvg, detail, Fonts.REGULAR, 10f, textMaxW), x + 16f, y + 41f, 10f,
                Theme.TEXT_SECONDARY);

        // the same three buttons a config profile row has, with use in place of load
        float buttonY = y + (CARD_H - AetherButton.ROW_H) / 2f;
        float useX = x + w - 12f - buttonsW;
        float editX = useX + AetherButton.ROW_W + AetherButton.ROW_GAP;
        float deleteX = editX + AetherButton.ROW_W + AetherButton.ROW_GAP;
        renderRowButton(nvg, AetherLang.localize(selected ? "In Use" : "Use"), useX, buttonY, AetherButton.ROW_W,
                selected ? AetherButton.Kind.ACTIVE : AetherButton.Kind.NORMAL, mx, my, () -> toggleSelected(route));
        renderRowButton(nvg, AetherLang.localize("Edit"), editX, buttonY, AetherButton.ROW_W,
                AetherButton.Kind.NORMAL, mx, my, () -> RouteEditor.begin(Minecraft.getInstance(), folder, route));
        renderRowButton(nvg, AetherLang.localize("Delete"), deleteX, buttonY, AetherButton.ROW_W,
                AetherButton.Kind.DANGER, mx, my, () -> deleteRoute(route));
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
        store.delete(folder, route.name());
        NotificationManager.success(AetherLang.localize("Route Deleted"), "\"" + route.name() + "\" deleted");
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
