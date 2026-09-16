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

// vertical list of every runnable macro, grouped by type, laid out like the module grid:
// same canvas scaling, same card treatment, same underlined filter bar and ruled section headers
public final class MacroStartScreen extends NVGScreen {

    private static final float PANEL_W = 380f;
    private static final float HEADER_H = 52f;
    private static final float SEARCH_H = 32f;
    private static final float TAB_H = 34f;
    private static final float CARD_H = 68f;
    private static final float CARD_GAP = 8f;
    private static final float SECT_H = 26f;
    private static final float SECT_GAP = 10f;
    private static final float PAD = 18f;
    private static final float ICON = 16f;
    private static final float ICON_LANE = 62f;

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

        float listTopOffset = HEADER_H + 1f + 12f + SEARCH_H + 10f + TAB_H + 1f + 6f;
        float wanted = listTopOffset + measureList() + PAD;
        float panelH = Math.min(canvasH - 60f, Math.max(220f, wanted));
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
        renderContent(nvg, px, py, panelH, listTopOffset);
        nvg.restore();

        nvg.restore();
    }

    private void renderContent(NVGRenderer nvg, float px, float py, float panelH, float listTopOffset) {
        float mx = canvasMouseX();
        float my = canvasMouseY();
        float innerW = PANEL_W - PAD * 2f;

        String title = AetherLang.localize("Macros");
        nvg.text(Fonts.BOLD, title, px + PAD, py + (HEADER_H - 15f) / 2f, 15f, Theme.TEXT_PRIMARY);
        if (MacroCatalog.isStoppable()) {
            renderStopAll(nvg, px, py, mx, my);
        } else {
            float titleW = nvg.textWidth(Fonts.BOLD, title, 15f);
            float sepX = px + PAD + titleW + 10f;
            nvg.rect(sepX, py + (HEADER_H - 19f) / 2f, 1f, 19f, Theme.SEPARATOR);
            nvg.text(Fonts.REGULAR, AetherLang.localize("Start and stop macros"), sepX + 10f,
                    py + (HEADER_H - 12f) / 2f, 12f, Theme.TEXT_MUTED);
        }
        nvg.rect(px, py + HEADER_H, PANEL_W, 1f, Theme.SEPARATOR);

        float searchY = py + HEADER_H + 1f + 12f;
        renderSearch(nvg, px + PAD, searchY, innerW, mx, my);

        float tabsY = searchY + SEARCH_H + 10f;
        renderFilterBar(nvg, px, tabsY, mx, my);

        float listTop = py + listTopOffset;
        float listH = py + panelH - listTop - PAD;
        renderList(nvg, px + PAD, listTop, innerW, listH, mx, my);
    }

    private void renderStopAll(NVGRenderer nvg, float px, float py, float mx, float my) {
        String label = AetherLang.localize("Stop");
        float w = nvg.textWidth(Fonts.BOLD, label, 11f) + 24f;
        float h = 26f;
        float x = px + PANEL_W - PAD - w;
        float y = py + (HEADER_H - h) / 2f;
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + h;

        nvg.roundedRect(x, y, w, h, 6f, hovered ? Theme.ACTION_BTN_HOVER : Theme.ACTION_BTN_BG);
        nvg.rectOutlineSolid(x, y, w, h, 6f, 1f, Theme.withAlpha(Theme.ACCENT_ERROR, hovered ? 0.9f : 0.55f));
        nvg.textCentered(Fonts.BOLD, label, x, y, w, h, 11f, Theme.ACCENT_ERROR);
        hits.add(new Hit(x, y, w, h, MacroCatalog::stopEverything));
    }

    private void renderSearch(NVGRenderer nvg, float x, float y, float w, float mx, float my) {
        float radius = SEARCH_H / 2f;
        nvg.roundedRect(x, y, w, SEARCH_H, radius, Theme.BG_FIELD);
        nvg.rectOutlineSolid(x, y, w, SEARCH_H, radius, 1f,
                searchFocused ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
        nvg.renderSVG("/assets/aether/icons/search.svg", x + 12f, y + (SEARCH_H - 14f) / 2f, 14f, 14f,
                searchFocused ? Theme.ACCENT_PRIMARY : Theme.TEXT_MUTED);

        boolean empty = search.isEmpty();
        nvg.text(Fonts.REGULAR, empty ? AetherLang.localize("Search macros...") : search.toString(),
                x + 34f, y + (SEARCH_H - 12f) / 2f, 12f, empty ? Theme.TEXT_MUTED : Theme.TEXT_PRIMARY);
        if (searchFocused) {
            float caretX = x + 34f + nvg.textWidth(Fonts.REGULAR, search.toString(), 12f) + 1.5f;
            nvg.rect(caretX, y + 9f, 1f, SEARCH_H - 18f, Theme.ACCENT_PRIMARY);
        }
        hits.add(new Hit(x, y, w, SEARCH_H, () -> searchFocused = true));
    }

    // text tabs with an accent underline, the same shape as the module filter bar
    private void renderFilterBar(NVGRenderer nvg, float px, float y, float mx, float my) {
        float tabX = px + PAD;
        for (String type : MacroCatalog.types()) {
            String label = AetherLang.localize(type) + " (" + MacroCatalog.countOfType(type) + ")";
            float labelW = nvg.textWidth(Fonts.REGULAR, label, 12f);
            boolean on = ACTIVE_FILTERS.contains(type);
            boolean hovered = mx >= tabX - 4f && mx <= tabX + labelW + 4f && my >= y && my <= y + TAB_H;

            int color = on ? Theme.ACCENT_PRIMARY : (hovered ? Theme.TEXT_VALUE : Theme.TEXT_MUTED);
            nvg.text(Fonts.REGULAR, label, tabX, y + (TAB_H - 12f) / 2f, 12f, color);
            if (on) {
                nvg.rect(tabX, y + TAB_H - 2f, labelW, 2f, Theme.ACCENT_PRIMARY);
            }

            final String filtered = type;
            hits.add(new Hit(tabX - 4f, y, labelW + 8f, TAB_H, () -> {
                if (!ACTIVE_FILTERS.remove(filtered)) {
                    ACTIVE_FILTERS.add(filtered);
                }
            }));
            tabX += labelW + 20f;
        }
        nvg.rect(px, y + TAB_H, PANEL_W, 1f, Theme.SEPARATOR);
    }

    private float measureList() {
        float total = 0f;
        for (String type : MacroCatalog.types()) {
            List<MacroCatalog.Entry> shown = visibleEntriesOfType(type);
            if (shown.isEmpty()) {
                continue;
            }
            total += SECT_H + shown.size() * (CARD_H + CARD_GAP) + SECT_GAP;
        }
        return Math.max(CARD_H, total);
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

            if (rowY + SECT_H > y && rowY < y + h) {
                String label = AetherLang.localize(type);
                nvg.text(Fonts.REGULAR, label, x, rowY + (SECT_H - 11f) / 2f, 11f, Theme.TEXT_MUTED);
                float labelW = nvg.textWidth(Fonts.REGULAR, label, 11f);
                float ruleX = x + labelW + 10f;
                nvg.rect(ruleX, rowY + SECT_H / 2f, Math.max(0f, x + w - ruleX), 1f, Theme.SEPARATOR);
            }
            rowY += SECT_H;
            total += SECT_H;

            for (MacroCatalog.Entry entry : shown) {
                if (rowY + CARD_H > y && rowY < y + h) {
                    renderCard(nvg, entry, x, rowY, w, mx, my);
                }
                rowY += CARD_H + CARD_GAP;
                total += CARD_H + CARD_GAP;
            }
            rowY += SECT_GAP;
            total += SECT_GAP;
        }

        nvg.popScissor();
        maxScrollY = Math.max(0f, total - h);
        scrollY = Math.max(0f, Math.min(maxScrollY, scrollY));
    }

    private void renderCard(NVGRenderer nvg, MacroCatalog.Entry entry, float x, float y, float w,
                            float mx, float my) {
        boolean running = entry.isRunning();
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + CARD_H;

        nvg.roundedRect(x, y, w, CARD_H, 8f, Theme.CARD_BG);
        if (running) {
            nvg.roundedRect(x, y, w, CARD_H, 8f, Theme.withAlpha(Theme.ACCENT_ENABLED, 0.12f));
        }
        nvg.rectOutlineSolid(x, y, w, CARD_H, 8f, 1f,
                running ? Theme.withAlpha(Theme.ACCENT_ENABLED, 0.75f)
                        : (hovered ? Theme.BORDER_HOVER : Theme.SEPARATOR));

        float textMaxW = w - 32f - ICON_LANE;
        nvg.text(Fonts.BOLD, fit(nvg, AetherLang.localize(entry.displayName()), Fonts.BOLD, 13f, textMaxW),
                x + 16f, y + 17f, 13f, Theme.TEXT_PRIMARY);
        nvg.text(Fonts.REGULAR, fit(nvg, AetherLang.localize(entry.description()), Fonts.REGULAR, 10f, textMaxW),
                x + 16f, y + 39f, 10f, Theme.TEXT_SECONDARY);

        float settingsX = x + w - 16f - ICON;
        float startX = settingsX - ICON - 18f;
        float iconY = y + (CARD_H - ICON) / 2f;

        boolean settingsHover = hitNear(mx, my, settingsX, iconY);
        nvg.renderSVG("/assets/aether/icons/settings.svg", settingsX, iconY, ICON, ICON,
                settingsHover ? Theme.TEXT_PRIMARY : Theme.TEXT_MUTED);
        hits.add(new Hit(settingsX - 7f, iconY - 7f, ICON + 14f, ICON + 14f, () -> openSettings(entry)));

        boolean startHover = hitNear(mx, my, startX, iconY);
        int startColor = running ? Theme.ACCENT_ERROR : Theme.ACCENT_ENABLED;
        nvg.renderSVG(running ? "/assets/aether/icons/stop.svg" : "/assets/aether/icons/play.svg",
                startX, iconY, ICON, ICON, startHover ? startColor : Theme.withAlpha(startColor, 0.85f));
        hits.add(new Hit(startX - 7f, iconY - 7f, ICON + 14f, ICON + 14f, () -> MacroCatalog.toggle(entry)));
    }

    // the card clips at the icon lane, so an over long line is cut rather than run under the icons
    private static String fit(NVGRenderer nvg, String text, String font, float size, float maxW) {
        if (nvg.textWidth(font, text, size) <= maxW) {
            return text;
        }
        String trimmed = text;
        while (!trimmed.isEmpty() && nvg.textWidth(font, trimmed + "...", size) > maxW) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "...";
    }

    private static boolean hitNear(float mx, float my, float x, float y) {
        return mx >= x - 7f && mx <= x + ICON + 7f && my >= y - 7f && my <= y + ICON + 7f;
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
                    && !entry.description().toLowerCase(Locale.ROOT).contains(needle)
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
