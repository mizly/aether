package dev.aether.ui;

import dev.aether.macro.MacroCatalog;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NVGScreen;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
// drawn in the same scaled canvas as MainGUI so ui scale, text scale and the theme all carry over
public final class MacroStartScreen extends NVGScreen {

    private static final float PANEL_W = 340f;
    private static final float PANEL_MAX_H = 520f;
    private static final float HEADER_H = 46f;
    private static final float SEARCH_H = 30f;
    private static final float CHIP_H = 24f;
    private static final float ROW_H = 42f;
    private static final float TYPE_H = 26f;
    private static final float PAD = 20f;
    private static final float ICON = 16f;

    // kept between openings, so a filter stays set until it is cleared again
    private static final Set<String> ACTIVE_FILTERS = new LinkedHashSet<>();

    private final List<Hit> hits = new ArrayList<>();
    private final StringBuilder search = new StringBuilder();
    private boolean searchFocused;
    private float scrollY;
    private float maxScrollY;

    private float alpha;
    private float animScale = 0.96f;
    private float animOffsetY = 120f;

    private float pr = 1f;
    private float rawMouseX;
    private float rawMouseY;

    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean contains(float mx, float my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    public MacroStartScreen() {
        super("Macros");
    }

    @Override
    protected void renderNVG(NVGRenderer nvg) {
        syncPixelRatio();
        float step = Math.min(1f, Theme.animationFactor() * 6f);
        alpha += (1f - alpha) * step;
        animScale += (1f - animScale) * step * 1.1f;
        animOffsetY += (0f - animOffsetY) * step * 1.1f;

        hits.clear();

        nvg.save();
        nvg.scale(MainGUI.uiScale / pr, MainGUI.uiScale / pr);
        nvg.setTextScale(MainGUI.uiTextScale);

        float canvasW = width * pr / MainGUI.uiScale;
        float canvasH = height * pr / MainGUI.uiScale;
        nvg.rect(0f, 0f, canvasW, canvasH, Theme.withAlpha(0xFF000000, (int) (alpha * 170)));

        float panelH = Math.min(PANEL_MAX_H, Math.max(240f, canvasH - 60f));
        float px = (canvasW - PANEL_W) / 2f;
        float py = (canvasH - panelH) / 2f;
        float scx = canvasW / 2f;
        float scy = canvasH / 2f;

        nvg.save();
        nvg.translate(scx, scy);
        nvg.scale(animScale, animScale);
        nvg.translate(-scx, -scy);
        nvg.translate(0f, animOffsetY);
        nvg.globalAlpha(alpha);
        nvg.shadow(px, py, PANEL_W, panelH, MainGUI.RADIUS, 26f, Theme.withAlpha(0xFF000000, 0.75f));
        nvg.roundedRect(px, py, PANEL_W, panelH, MainGUI.RADIUS, Theme.PANEL_BG);
        nvg.rectOutline(px, py, PANEL_W, panelH, MainGUI.RADIUS, 1f, Theme.BORDER_DEFAULT);
        nvg.restore();

        // text keeps its final size through the zoom so it never jitters, matching MainGUI
        nvg.save();
        nvg.translate(0f, animOffsetY);
        nvg.globalAlpha(alpha);
        renderContent(nvg, px, py, panelH);
        nvg.restore();

        nvg.restore();
    }

    private void renderContent(NVGRenderer nvg, float px, float py, float panelH) {
        float mx = canvasMouseX();
        float my = canvasMouseY();

        nvg.text(Fonts.BOLD, AetherLang.localize("Macros"), px + PAD, py + (HEADER_H - 15f) / 2f, 15f,
                Theme.TEXT_PRIMARY);
        if (MacroCatalog.isStoppable()) {
            renderStopAll(nvg, px, py, mx, my);
        }
        nvg.rect(px, py + HEADER_H, PANEL_W, 1f, Theme.SEPARATOR);

        float searchY = py + HEADER_H + 14f;
        renderSearch(nvg, px + PAD, searchY, PANEL_W - PAD * 2f, mx, my);

        float chipsY = searchY + SEARCH_H + 12f;
        renderFilters(nvg, px + PAD, chipsY, mx, my);

        float listTop = chipsY + CHIP_H + 12f;
        float listH = py + panelH - listTop - 14f;
        renderList(nvg, px + PAD, listTop, PANEL_W - PAD * 2f, listH, mx, my);
    }

    private void renderStopAll(NVGRenderer nvg, float px, float py, float mx, float my) {
        String label = AetherLang.localize("Stop");
        float w = nvg.textWidth(Fonts.BOLD, label, 11f) + 22f;
        float h = 24f;
        float x = px + PANEL_W - PAD - w;
        float y = py + (HEADER_H - h) / 2f;
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + h;

        nvg.roundedRect(x, y, w, h, 6f, hovered ? Theme.ACTION_BTN_HOVER : Theme.ACTION_BTN_BG);
        nvg.rectOutlineSolid(x, y, w, h, 6f, 1f, Theme.withAlpha(Theme.ACCENT_ERROR, hovered ? 0.9f : 0.55f));
        nvg.textCentered(Fonts.BOLD, label, x, y, w, h, 11f, Theme.ACCENT_ERROR);
        hits.add(new Hit(x, y, w, h, MacroCatalog::stopEverything));
    }

    private void renderSearch(NVGRenderer nvg, float x, float y, float w, float mx, float my) {
        nvg.roundedRect(x, y, w, SEARCH_H, 7f, Theme.BG_FIELD);
        nvg.rectOutlineSolid(x, y, w, SEARCH_H, 7f, 1f,
                searchFocused ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
        nvg.renderSVG("/assets/aether/icons/search.svg", x + 8f, y + (SEARCH_H - 14f) / 2f, 14f, 14f,
                searchFocused ? Theme.ACCENT_PRIMARY : Theme.TEXT_MUTED);

        boolean empty = search.isEmpty();
        nvg.text(Fonts.REGULAR, empty ? AetherLang.localize("Search") : search.toString(),
                x + 30f, y + (SEARCH_H - 12f) / 2f, 12f, empty ? Theme.TEXT_MUTED : Theme.TEXT_PRIMARY);
        if (searchFocused) {
            float caretX = x + 30f + nvg.textWidth(Fonts.REGULAR, search.toString(), 12f) + 1.5f;
            nvg.rect(caretX, y + 8f, 1f, SEARCH_H - 16f, Theme.ACCENT_PRIMARY);
        }
        hits.add(new Hit(x, y, w, SEARCH_H, () -> searchFocused = true));
    }

    private void renderFilters(NVGRenderer nvg, float x, float y, float mx, float my) {
        float chipX = x;
        for (String type : MacroCatalog.types()) {
            String label = AetherLang.localize(type) + " (" + MacroCatalog.countOfType(type) + ")";
            float chipW = nvg.textWidth(Fonts.REGULAR, label, 11f) + 20f;
            boolean on = ACTIVE_FILTERS.contains(type);
            boolean hovered = mx >= chipX && mx <= chipX + chipW && my >= y && my <= y + CHIP_H;

            nvg.roundedRect(chipX, y, chipW, CHIP_H, 12f,
                    on ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.22f)
                       : (hovered ? Theme.ACTION_BTN_HOVER : Theme.ELEMENT_BG));
            nvg.rectOutlineSolid(chipX, y, chipW, CHIP_H, 12f, 1f,
                    on ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
            nvg.textCentered(Fonts.REGULAR, label, chipX, y, chipW, CHIP_H, 11f,
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
                        rowY + (TYPE_H - 9f) / 2f, 9f, Theme.withAlpha(Theme.TEXT_MUTED, 185));
            }
            rowY += TYPE_H;
            total += TYPE_H;

            for (MacroCatalog.Entry entry : shown) {
                if (rowY + ROW_H > y && rowY < y + h) {
                    renderRow(nvg, entry, x, rowY, w, mx, my);
                }
                rowY += ROW_H + 6f;
                total += ROW_H + 6f;
            }
            rowY += 8f;
            total += 8f;
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
        if (running) {
            nvg.roundedRect(x, y, w, ROW_H, 7f, Theme.withAlpha(Theme.ACCENT_ENABLED, 0.10f));
        }
        nvg.rectOutlineSolid(x, y, w, ROW_H, 7f, 1f,
                running ? Theme.withAlpha(Theme.ACCENT_ENABLED, 0.75f)
                        : (hovered ? Theme.BORDER_HOVER : Theme.SEPARATOR));
        nvg.text(Fonts.REGULAR, AetherLang.localize(entry.displayName()), x + 14f,
                y + (ROW_H - 12f) / 2f, 12f, Theme.TEXT_PRIMARY);

        float settingsX = x + w - 14f - ICON;
        float startX = settingsX - ICON - 16f;
        float iconY = y + (ROW_H - ICON) / 2f;

        boolean settingsHover = hitNear(mx, my, settingsX, iconY);
        nvg.renderSVG("/assets/aether/icons/settings.svg", settingsX, iconY, ICON, ICON,
                settingsHover ? Theme.TEXT_PRIMARY : Theme.TEXT_MUTED);
        hits.add(new Hit(settingsX - 6f, iconY - 6f, ICON + 12f, ICON + 12f, () -> openSettings(entry)));

        boolean startHover = hitNear(mx, my, startX, iconY);
        int startColor = running ? Theme.ACCENT_ERROR : Theme.ACCENT_ENABLED;
        nvg.renderSVG(running ? "/assets/aether/icons/stop.svg" : "/assets/aether/icons/play.svg",
                startX, iconY, ICON, ICON, startHover ? startColor : Theme.withAlpha(startColor, 0.8f));
        hits.add(new Hit(startX - 6f, iconY - 6f, ICON + 12f, ICON + 12f, () -> MacroCatalog.toggle(entry)));
    }

    private static boolean hitNear(float mx, float my, float x, float y) {
        return mx >= x - 6f && mx <= x + ICON + 6f && my >= y - 6f && my <= y + ICON + 6f;
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
        MainGUIRegistry.refresh();
        Minecraft.getInstance().setScreen(
                new MainGUI(new MainGUI.LaunchTarget(0, entry.settingsModule(), true)));
    }

    private void syncPixelRatio() {
        try {
            var target = Minecraft.getInstance().getMainRenderTarget();
            pr = width > 0 ? (float) target.width / width : 1f;
        } catch (Exception ignored) {
            pr = 1f;
        }
    }

    private float canvasMouseX() {
        return rawMouseX * pr / MainGUI.uiScale;
    }

    private float canvasMouseY() {
        return rawMouseY * pr / MainGUI.uiScale;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        rawMouseX = mouseX;
        rawMouseY = mouseY;
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() != 0) {
            return true;
        }
        float mx = (float) click.x() * pr / MainGUI.uiScale;
        float my = (float) click.y() * pr / MainGUI.uiScale;
        searchFocused = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mx, my)) {
                hit.action().run();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double verticalScroll) {
        scrollY = Math.max(0f, Math.min(maxScrollY, scrollY - (float) verticalScroll * 20f));
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
