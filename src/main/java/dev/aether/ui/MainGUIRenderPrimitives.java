package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

import java.util.IdentityHashMap;
import java.util.List;

final class MainGUIRenderPrimitives {
    private final MainGUI owner;
    private final MainGUITextLayout textLayout;
    private final IdentityHashMap<Object, Float> pillAnim = new IdentityHashMap<>();

    MainGUIRenderPrimitives(MainGUI owner, MainGUITextLayout textLayout) {
        this.owner = owner;
        this.textLayout = textLayout;
    }

    void renderSectionHeader(NVGRenderer nvg, String name, float x, float y, float w) {
        name = AetherLang.localize(name);
        float textW = nvg.textWidth(Fonts.BOLD, name, 11f);
        nvg.text(Fonts.BOLD, name, x, y + (MainGUI.SECT_H - 11f) / 2f, 11f, Theme.TEXT_MUTED);
        float lineX = x + textW + 10f;
        float lineY = y + MainGUI.SECT_H / 2f;
        nvg.rect(lineX, lineY, w - textW - 10f, 1f, Theme.SEPARATOR);
    }

    void renderModuleCard(NVGRenderer nvg, ModulesTab.SubTab subTab, float x, float y, float w, float hover, float mx, float my) {
        float height = MainGUI.CARD_H;
        float lift = hover * 4f;
        boolean enabled = subTab.isEnabled();

        nvg.save();
        nvg.translate(0f, -lift);

        if (hover > 0.01f) {
            nvg.shadow(x, y, w, height, 7f, 14f, Theme.withAlpha(Theme.ACCENT_PRIMARY, hover * 0.18f));
        }

        nvg.roundedRect(x, y, w, height, 7f, Theme.CARD_BG);
        if (enabled) {
            nvg.roundedRect(x, y, w, height, 7f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.12f));
            nvg.rectOutlineSolid(x, y, w, height, 7f, 1f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.4f));
        } else {
            int border = hover > 0.01f ? Theme.blend(Theme.SEPARATOR, Theme.BORDER_HOVER, hover) : Theme.SEPARATOR;
            nvg.rectOutlineSolid(x, y, w, height, 7f, 1f, border);
            if (hover > 0.01f) {
                nvg.roundedRect(x, y, w, height, 7f, Theme.withAlpha(0xFFFFFFFF, hover * 0.04f));
            }
        }

        float pillX = x + w - 52f;
        float pillY = y + (height - MainGUI.PILL_H) / 2f;
        if (subTab.hasToggle()) {
            renderPill(nvg, pillX, pillY, enabled, subTab);
        }

        float textX = x + 16f;
        int nameColor = enabled ? Theme.TEXT_PRIMARY : Theme.TEXT_VALUE;
        int descriptionColor = enabled ? Theme.TEXT_SECONDARY : Theme.TEXT_MUTED;
        float textMaxW = Math.max(80f, w - 32f - (subTab.hasToggle() ? 62f : 0f));
        List<String> nameLines = textLayout.wrapTextToWidth(nvg, AetherLang.localize(subTab.name()), Fonts.BOLD, 13f, textMaxW);
        List<String> descriptionLines = textLayout.wrapTextToWidth(nvg, AetherLang.localize(subTab.description()), Fonts.REGULAR, 10f, textMaxW);
        float descriptionY = y + 38f + Math.max(0, Math.min(2, nameLines.size()) - 1) * 12f;
        for (int i = 0; i < Math.min(2, nameLines.size()); i++) {
            nvg.text(Fonts.BOLD, nameLines.get(i), textX, y + 19f + i * 15f, 13f, nameColor);
        }
        for (int i = 0; i < Math.min(2, descriptionLines.size()); i++) {
            nvg.text(Fonts.REGULAR, descriptionLines.get(i), textX, descriptionY + i * 12f, 10f, descriptionColor);
        }

        nvg.restore();

        if (subTab.hasToggle()) {
            owner.addClickArea(pillX - 6f, pillY - 6f, 50f, MainGUI.PILL_H + 12f, subTab::toggle);
        }
        owner.addClickArea(x, y, w - 56f, height, () -> owner.openModuleDetailFromCard(subTab));
    }

    void renderListActionButton(NVGRenderer nvg, float x, float y, float w, String label, boolean hovered, boolean enabled) {
        int bg = !enabled
                ? Theme.withAlpha(Theme.BG_FIELD, 0.7f)
                : hovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.16f) : Theme.BG_FIELD;
        int border = !enabled
                ? Theme.withAlpha(Theme.BORDER_DEFAULT, 0.6f)
                : hovered ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT;
        int text = !enabled
                ? Theme.TEXT_DIM
                : hovered ? Theme.ACCENT_PRIMARY : Theme.TEXT_SECONDARY;

        nvg.roundedRect(x, y, w, MainGUI.LIST_ITEM_H, 3f, bg);
        nvg.rectOutline(x, y, w, MainGUI.LIST_ITEM_H, 3f, 1f, border);
        nvg.textCentered(Fonts.BOLD, label, x, y + 0.5f, w, MainGUI.LIST_ITEM_H, 12f, text);
    }

    void renderPill(NVGRenderer nvg, float x, float y, boolean on, Object key) {
        float t = pillAnimValue(key, on);
        float trackW = 38f;
        float trackH = MainGUI.PILL_H;

        int offBg = Theme.PILL_TRACK;
        int onBg = Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.18f);
        nvg.roundedRect(x, y, trackW, trackH, 10f, Theme.blend(offBg, onBg, t));

        int offBorder = Theme.withAlpha(0xFFFFFFFF, 0.2f);
        int onBorder = Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.7f);
        nvg.rectOutlineSolid(x, y, trackW, trackH, 10f, 1f, Theme.blend(offBorder, onBorder, t));

        float knobX = x + 2f + t * 19f;
        float knobY = y + 3f;
        nvg.roundedRect(knobX, knobY, 15f, 15f, 7.5f, Theme.blend(Theme.PILL_KNOB_OFF, Theme.ACCENT_PRIMARY, t));
    }

    void renderDropdownActionButtons(NVGRenderer nvg, DropdownSetting dropdown, float fieldX, float fieldY, float fieldH, float mx, float my) {
        float startX = textLayout.dropdownActionStartX(dropdown, fieldX);
        if (startX >= fieldX) {
            return;
        }

        float iconSize = 16f;
        for (int i = 0; i < dropdown.getIconActions().size(); i++) {
            DropdownSetting.IconAction action = dropdown.getIconActions().get(i);
            float actionX = startX + i * (MainGUI.DROPDOWN_ACTION_W + MainGUI.DROPDOWN_ACTION_GAP);
            boolean hovered = mx >= actionX && mx <= actionX + MainGUI.DROPDOWN_ACTION_W
                    && my >= fieldY && my <= fieldY + fieldH;
            int bg = hovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.12f) : Theme.ELEMENT_BG;
            int border = hovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.4f) : Theme.BORDER_DEFAULT;
            int iconColor = hovered ? Theme.ACCENT_PRIMARY : Theme.TEXT_MUTED;

            nvg.roundedRect(actionX, fieldY, MainGUI.DROPDOWN_ACTION_W, fieldH, 7f, bg);
            nvg.rectOutline(actionX, fieldY, MainGUI.DROPDOWN_ACTION_W, fieldH, 7f, 1f, border);
            nvg.renderSVG(action.iconPath(),
                    actionX + (MainGUI.DROPDOWN_ACTION_W - iconSize) / 2f,
                    fieldY + (fieldH - iconSize) / 2f,
                    iconSize, iconSize, iconColor);
        }
    }

    private float pillAnimValue(Object key, boolean on) {
        float target = on ? 1f : 0f;
        float current = pillAnim.getOrDefault(key, target);
        float animationFactor = Theme.animationFactor();
        current += (target - current) * Math.min(1f, animationFactor * 3f);
        pillAnim.put(key, current);
        return current;
    }
}
