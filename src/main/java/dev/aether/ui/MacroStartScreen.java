package dev.aether.ui;

import dev.aether.macro.MacroCatalog;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NVGScreen;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// vertical list of every runnable macro, grouped by type, with start and settings on each row
public final class MacroStartScreen extends NVGScreen {

    private static final float PANEL_W = 320f;
    private static final float PANEL_MAX_H = 460f;
    private static final float HEADER_H = 42f;
    private static final float SEARCH_H = 28f;
    private static final float CHIP_H = 24f;
    private static final float ROW_H = 40f;
    private static final float TYPE_H = 24f;
    private static final float PAD = 12f;
    private static final float ICON = 15f;

    // kept between openings, so a filter stays set until it is cleared again
    private static final Set<String> ACTIVE_FILTERS = new LinkedHashSet<>();

    private final List<Hit> hits = new ArrayList<>();
    private final StringBuilder search = new StringBuilder();
    private boolean searchFocused;
    private float scrollY;
    private float maxScrollY;

    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    public MacroStartScreen() {
        super("Macros");
    }

    @Override
    protected void renderNVG(NVGRenderer nvg) {
        hits.clear();

        float panelH = Math.min(PANEL_MAX_H, Math.max(220f, height - 60f));
        float px = (width - PANEL_W) / 2f;
        float py = (height - panelH) / 2f;

        nvg.shadow(px, py, PANEL_W, panelH, 10f, 26f, Theme.withAlpha(0xFF000000, 0.45f));
        nvg.roundedRect(px, py, PANEL_W, panelH, 10f, Theme.PANEL_BG);
        nvg.rectOutlineSolid(px, py, PANEL_W, panelH, 10f, 1f, Theme.SEPARATOR);

        float mx = lastMouseX;
        float my = lastMouseY;

        nvg.text(Fonts.BOLD, AetherLang.localize("Macros"), px + PAD, py + (HEADER_H - 14f) / 2f, 14f,
                Theme.TEXT_PRIMARY);
        boolean anythingRunning = MacroCatalog.isStoppable();
        if (anythingRunning) {
            String stopAll = AetherLang.localize("Stop");
            float stopW = nvg.textWidth(Fonts.BOLD, stopAll, 10f) + 18f;
            float stopX = px + PANEL_W - PAD - stopW;
            float stopY = py + (HEADER_H - 20f) / 2f;
            boolean hovered = mx >= stopX && mx <= stopX + stopW && my >= stopY && my <= stopY + 20f;
            nvg.roundedRect(stopX, stopY, stopW, 20f, 5f,
                    Theme.withAlpha(Theme.ACCENT_ERROR, hovered ? 0.30f : 0.18f));
            nvg.textCentered(Fonts.BOLD, stopAll, stopX, stopY, stopW, 20f, 10f, Theme.ACCENT_ERROR);
            hits.add(new Hit(stopX, stopY, stopW, 20f, MacroCatalog::stopEverything));
        }
        nvg.rect(px, py + HEADER_H, PANEL_W, 1f, Theme.SEPARATOR);

        float searchY = py + HEADER_H + PAD;
        renderSearch(nvg, px + PAD, searchY, PANEL_W - PAD * 2f, mx, my);

        float chipsY = searchY + SEARCH_H + 10f;
        renderFilters(nvg, px + PAD, chipsY, mx, my);

        float listTop = chipsY + CHIP_H + 10f;
        float listH = py + panelH - listTop - PAD;
        renderList(nvg, px + PAD, listTop, PANEL_W - PAD * 2f, listH, mx, my);
    }

    private void renderSearch(NVGRenderer nvg, float x, float y, float w, float mx, float my) {
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + SEARCH_H;
        nvg.roundedRect(x, y, w, SEARCH_H, 6f, Theme.BG_FIELD);
        nvg.rectOutlineSolid(x, y, w, SEARCH_H, 6f, 1f,
                searchFocused ? Theme.ACCENT_PRIMARY : (hovered ? Theme.BORDER_HOVER : Theme.BORDER_DEFAULT));
        nvg.renderSVG("/assets/aether/icons/search.svg", x + 8f, y + (SEARCH_H - 13f) / 2f, 13f, 13f,
                Theme.TEXT_MUTED);

        String shown = search.isEmpty() ? AetherLang.localize("Search") : search.toString();
        int color = search.isEmpty() ? Theme.TEXT_MUTED : Theme.TEXT_PRIMARY;
        nvg.text(Fonts.REGULAR, shown, x + 27f, y + (SEARCH_H - 11f) / 2f, 11f, color);
        if (searchFocused) {
            float caretX = x + 27f + nvg.textWidth(Fonts.REGULAR, search.toString(), 11f) + 1.5f;
            nvg.rect(caretX, y + 7f, 1f, SEARCH_H - 14f, Theme.TEXT_PRIMARY);
        }
        hits.add(new Hit(x, y, w, SEARCH_H, () -> searchFocused = true));
    }

    private void renderFilters(NVGRenderer nvg, float x, float y, float mx, float my) {
        float chipX = x;
        for (String type : MacroCatalog.types()) {
            String label = AetherLang.localize(type) + " (" + MacroCatalog.countOfType(type) + ")";
            float chipW = nvg.textWidth(Fonts.REGULAR, label, 10f) + 18f;
            boolean on = ACTIVE_FILTERS.contains(type);
            boolean hovered = mx >= chipX && mx <= chipX + chipW && my >= y && my <= y + CHIP_H;

            nvg.roundedRect(chipX, y, chipW, CHIP_H, 12f,
                    on ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.22f)
                       : Theme.withAlpha(Theme.TEXT_MUTED, hovered ? 0.16f : 0.08f));
            nvg.rectOutlineSolid(chipX, y, chipW, CHIP_H, 12f, 1f,
                    on ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
            nvg.textCentered(Fonts.REGULAR, label, chipX, y, chipW, CHIP_H, 10f,
                    on ? Theme.ACCENT_PRIMARY : Theme.TEXT_SECONDARY);

            final String filtered = type;
            hits.add(new Hit(chipX, y, chipW, CHIP_H, () -> {
                if (!ACTIVE_FILTERS.remove(filtered)) {
                    ACTIVE_FILTERS.add(filtered);
                }
            }));
            chipX += chipW + 6f;
        }
    }

    private void renderList(NVGRenderer nvg, float x, float y, float w, float h, float mx, float my) {
        nvg.pushScissor(x, y, w, h);
        float rowY = y - scrollY;
        float total = 0f;

        for (String type : MacroCatalog.types()) {
            List<MacroCatalog.Entry> shown = visibleEntriesOfType(type);
            if (shown.isEmpty()) {
                continue;
            }

            if (rowY + TYPE_H > y && rowY < y + h) {
                nvg.text(Fonts.BOLD, AetherLang.localize(type).toUpperCase(Locale.ROOT), x + 2f,
                        rowY + (TYPE_H - 9f) / 2f, 9f, Theme.withAlpha(Theme.TEXT_MUTED, 200));
            }
            rowY += TYPE_H;
            total += TYPE_H;

            for (MacroCatalog.Entry entry : shown) {
                if (rowY + ROW_H > y && rowY < y + h) {
                    renderRow(nvg, entry, x, rowY, w, mx, my);
                }
                rowY += ROW_H + 4f;
                total += ROW_H + 4f;
            }
            rowY += 6f;
            total += 6f;
        }

        nvg.popScissor();
        maxScrollY = Math.max(0f, total - h);
        scrollY = Math.max(0f, Math.min(maxScrollY, scrollY));
    }

    private void renderRow(NVGRenderer nvg, MacroCatalog.Entry entry, float x, float y, float w,
                           float mx, float my) {
        boolean running = entry.isRunning();
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + ROW_H;

        nvg.roundedRect(x, y, w, ROW_H, 7f, Theme.CARD_BG);
        nvg.rectOutlineSolid(x, y, w, ROW_H, 7f, 1f,
                running ? Theme.withAlpha(Theme.ACCENT_ENABLED, 0.7f)
                        : (hovered ? Theme.BORDER_HOVER : Theme.SEPARATOR));
        nvg.text(Fonts.REGULAR, AetherLang.localize(entry.displayName()), x + 12f,
                y + (ROW_H - 12f) / 2f, 12f, Theme.TEXT_PRIMARY);

        float settingsX = x + w - PAD - ICON;
        float startX = settingsX - ICON - 14f;
        float iconY = y + (ROW_H - ICON) / 2f;

        boolean settingsHover = mx >= settingsX - 5f && mx <= settingsX + ICON + 5f
                && my >= iconY - 5f && my <= iconY + ICON + 5f;
        nvg.renderSVG("/assets/aether/icons/settings.svg", settingsX, iconY, ICON, ICON,
                settingsHover ? Theme.TEXT_PRIMARY : Theme.TEXT_MUTED);
        hits.add(new Hit(settingsX - 5f, iconY - 5f, ICON + 10f, ICON + 10f,
                () -> openSettings(entry)));

        boolean startHover = mx >= startX - 5f && mx <= startX + ICON + 5f
                && my >= iconY - 5f && my <= iconY + ICON + 5f;
        int startColor = running ? Theme.ACCENT_ERROR : Theme.ACCENT_ENABLED;
        nvg.renderSVG(running ? "/assets/aether/icons/stop.svg" : "/assets/aether/icons/play.svg",
                startX, iconY, ICON, ICON,
                startHover ? startColor : Theme.withAlpha(startColor, 0.75f));
        hits.add(new Hit(startX - 5f, iconY - 5f, ICON + 10f, ICON + 10f,
                () -> MacroCatalog.toggle(entry)));
    }

    private List<MacroCatalog.Entry> visibleEntriesOfType(String type) {
        String needle = search.toString().toLowerCase(Locale.ROOT).trim();
        List<MacroCatalog.Entry> shown = new ArrayList<>();
        for (MacroCatalog.Entry entry : MacroCatalog.entries()) {
            if (!entry.type().equals(type)) {
                continue;
            }
            if (!ACTIVE_FILTERS.isEmpty() && !ACTIVE_FILTERS.contains(type)) {
                continue;
            }
            if (!needle.isEmpty()
                    && !entry.displayName().toLowerCase(Locale.ROOT).contains(needle)
                    && !type.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            shown.add(entry);
        }
        return shown;
    }

    private void openSettings(MacroCatalog.Entry entry) {
        Minecraft client = Minecraft.getInstance();
        MainGUIRegistry.refresh();
        client.setScreen(new MainGUI(new MainGUI.LaunchTarget(0, entry.settingsModule(), true)));
    }

    private float lastMouseX;
    private float lastMouseY;

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() != 0) {
            return true;
        }
        searchFocused = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(click.x(), click.y())) {
                hit.action().run();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY2) {
        scrollY = Math.max(0f, Math.min(maxScrollY, scrollY - (float) scrollY2 * 18f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (searchFocused && input.key() == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search.deleteCharAt(search.length() - 1);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (searchFocused) {
            char typed = (char) input.codepoint();
            if (typed >= ' ') {
                search.append(typed);
                return true;
            }
        }
        return super.charTyped(input);
    }
}
