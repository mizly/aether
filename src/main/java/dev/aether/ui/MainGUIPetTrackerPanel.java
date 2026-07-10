package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

final class MainGUIPetTrackerPanel {
    private final MainGUI owner;

    MainGUIPetTrackerPanel(MainGUI owner) {
        this.owner = owner;
    }

    void renderGroup(NVGRenderer nvg, SettingGroup group, float x, float y, float w, float mx, float my) {
        nvg.rect(x, y, w, 1f, Theme.BORDER_DEFAULT);

        Fields fields = resolveFields(group);
        if (fields == null) {
            return;
        }

        Layout layout = new Layout(x, y, w);
        renderCompactTextField(nvg, fields.petName, layout.nameX, layout.row1Y, layout.nameW, MainGUI.petFieldHeight());
        renderCompactDropdownField(nvg, fields.maxLevel, layout.maxLevelX, layout.row1Y, layout.quarterW, MainGUI.petFieldHeight());
        renderCompactDropdownField(nvg, fields.rarity, layout.rarityX, layout.row1Y, layout.quarterW, MainGUI.petFieldHeight());
        renderCompactTextField(nvg, fields.level1Price, layout.level1X, layout.row2Y, layout.halfW, MainGUI.petFieldHeight());
        renderCompactTextField(nvg, fields.maxLevelPrice, layout.maxLevelX2, layout.row2Y, layout.halfW, MainGUI.petFieldHeight());
    }

    boolean handleGroupClick(SettingGroup group, float mx, float my, float x, float y, float w) {
        Fields fields = resolveFields(group);
        if (fields == null) {
            return false;
        }

        Layout layout = new Layout(x, y, w);
        float fieldY1 = compactFieldBodyY(layout.row1Y);
        float fieldY2 = compactFieldBodyY(layout.row2Y);
        float fieldH = compactFieldBodyH(MainGUI.petFieldHeight());

        if (my >= fieldY1 && my <= fieldY1 + fieldH) {
            if (mx >= layout.nameX && mx <= layout.nameX + layout.nameW) {
                handleCompactTextFieldClick(fields.petName, mx, my, layout.nameX, layout.row1Y, layout.nameW, MainGUI.petFieldHeight());
                return true;
            }
            if (mx >= layout.maxLevelX && mx <= layout.maxLevelX + layout.quarterW) {
                handleCompactDropdownFieldClick(fields.maxLevel, layout.maxLevelX, layout.row1Y, layout.quarterW, MainGUI.petFieldHeight());
                return true;
            }
            if (mx >= layout.rarityX && mx <= layout.rarityX + layout.quarterW) {
                handleCompactDropdownFieldClick(fields.rarity, layout.rarityX, layout.row1Y, layout.quarterW, MainGUI.petFieldHeight());
                return true;
            }
        }

        if (my >= fieldY2 && my <= fieldY2 + fieldH) {
            if (mx >= layout.level1X && mx <= layout.level1X + layout.halfW) {
                handleCompactTextFieldClick(fields.level1Price, mx, my, layout.level1X, layout.row2Y, layout.halfW, MainGUI.petFieldHeight());
                return true;
            }
            if (mx >= layout.maxLevelX2 && mx <= layout.maxLevelX2 + layout.halfW) {
                handleCompactTextFieldClick(fields.maxLevelPrice, mx, my, layout.maxLevelX2, layout.row2Y, layout.halfW, MainGUI.petFieldHeight());
                return true;
            }
        }

        return false;
    }

    private void handleCompactTextFieldClick(TextSetting setting, float mx, float my, float x, float y, float w, float h) {
        owner.commitColor();

        boolean sameActive = owner.isActiveTextEditor(setting);
        if (!sameActive) {
            owner.commitText();
            owner.activateTextSettingEditor(setting);
        }

        float fieldY = compactFieldBodyY(y);
        float fieldH = compactFieldBodyH(h);
        float textPad = 6f;
        float fieldTextSize = 11f;
        String value = owner.currentTextBuffer();
        float visibleTextW = w - textPad * 2f;
        float textOffset = sameActive
                ? owner.inlineTextScrollOffsetApproxFor(value, owner.currentTextCursor(), fieldTextSize, visibleTextW)
                : 0f;
        float startX = x + textPad - textOffset;
        owner.beginInlineFieldCaretPress(x, fieldY, w, fieldH, value, fieldTextSize, startX, mx, my);
    }

    private void handleCompactDropdownFieldClick(DropdownSetting setting, float x, float y, float w, float h) {
        owner.commitText();
        owner.commitColor();

        float fieldY = compactFieldBodyY(y);
        float fieldH = compactFieldBodyH(h);
        if (owner.isOpenDropdown(setting)) {
            owner.closeOpenDropdown();
            return;
        }

        owner.openDropdownOverlayFor(setting, x, fieldY + fieldH, w, fieldH);
    }

    private Fields resolveFields(SettingGroup group) {
        Setting petName = findSetting(group, "Pet Name");
        Setting maxLevel = findSetting(group, "Max Level");
        Setting rarity = findSetting(group, "Rarity");
        Setting level1Price = findSetting(group, "Level 1 Price");
        Setting maxLevelPrice = findSetting(group, "Max Level Price");
        if (!(petName instanceof TextSetting petNameText)
                || !(maxLevel instanceof DropdownSetting maxLevelDropdown)
                || !(rarity instanceof DropdownSetting rarityDropdown)
                || !(level1Price instanceof TextSetting level1Text)
                || !(maxLevelPrice instanceof TextSetting maxLevelText)) {
            return null;
        }
        return new Fields(petNameText, maxLevelDropdown, rarityDropdown, level1Text, maxLevelText);
    }

    private Setting findSetting(SettingGroup group, String name) {
        for (Setting setting : group.getSettings()) {
            if (setting.getRawName().equals(name)) {
                return setting;
            }
        }
        return null;
    }

    private float compactFieldBodyY(float y) {
        return y + 18f;
    }

    private float compactFieldBodyH(float h) {
        return h - 18f;
    }

    private void renderCompactTextField(NVGRenderer nvg, TextSetting setting, float x, float y, float w, float h) {
        boolean active = owner.isActiveTextEditor(setting);
        nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getName()), x, y + 5f, 10f, Theme.TEXT_SECONDARY);
        float fieldY = compactFieldBodyY(y);
        float fieldH = compactFieldBodyH(h);
        nvg.roundedRect(x, fieldY, w, fieldH, 4f, Theme.BG_FIELD);
        nvg.rectOutline(x, fieldY, w, fieldH, 4f, 1f, active ? Theme.BORDER_ACTIVE : Theme.BORDER_DEFAULT);
        if (active) {
            owner.setEditorBounds(x, fieldY, w, fieldH);
        }

        float textPad = 6f;
        String value = active ? owner.currentTextBuffer() : setting.getValue();
        float visibleTextW = w - textPad * 2f;
        float textOffset = active
                ? owner.inlineTextScrollOffsetFor(nvg, Fonts.REGULAR, value, owner.currentTextCursor(), 11f, visibleTextW)
                : 0f;
        float drawX = x + textPad - textOffset;

        boolean clipped = owner.pushContentLocalScissor(nvg, x + textPad, fieldY + 1f, visibleTextW, fieldH - 2f);
        if (active && owner.textHasSelection()) {
            int selectionStart = owner.currentTextSelectionStart();
            int selectionEnd = owner.currentTextSelectionEnd();
            float selectionX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(value, 0, selectionStart), 11f);
            float selectionW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(value, selectionStart, selectionEnd), 11f);
            nvg.roundedRect(selectionX, fieldY + 4f, Math.max(1f, selectionW), fieldH - 8f, 3f,
                    Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f));
        }
        if (value.isEmpty() && !active) {
            nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getPlaceholder()), x + textPad, fieldY + 5f, 11f, Theme.TEXT_DIM);
        } else {
            nvg.text(Fonts.REGULAR, value, drawX, fieldY + 5f, 11f, Theme.TEXT_PRIMARY);
        }
        if (active && (System.currentTimeMillis() / 530) % 2 == 0) {
            String prefix = owner.safeTextSlice(owner.currentTextBuffer(), 0, owner.currentTextCursor());
            float caretX = drawX + nvg.textWidth(Fonts.REGULAR, prefix, 11f);
            nvg.rect(caretX, fieldY + 4f, 1f, fieldH - 8f, Theme.TEXT_PRIMARY);
        }
        owner.popContentLocalScissor(nvg, clipped);
    }

    private void renderCompactDropdownField(NVGRenderer nvg, DropdownSetting setting, float x, float y, float w, float h) {
        boolean open = owner.isOpenDropdown(setting);
        nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getName()), x, y + 5f, 10f, Theme.TEXT_SECONDARY);
        float fieldY = compactFieldBodyY(y);
        float fieldH = compactFieldBodyH(h);
        nvg.roundedRect(x, fieldY, w, fieldH, 4f, Theme.BG_FIELD);
        nvg.rectOutline(x, fieldY, w, fieldH, 4f, 1f, open ? Theme.BORDER_ACTIVE : Theme.BORDER_DEFAULT);
        boolean clipped = owner.pushContentLocalScissor(nvg, x + 7f, fieldY, w - 24f, fieldH);
        nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getSelectedOption()), x + 7f, fieldY + (fieldH - 13f) / 2f, 12f, Theme.TEXT_PRIMARY);
        owner.popContentLocalScissor(nvg, clipped);
        DropdownChevron.renderDown(nvg, x + w - 24f, fieldY, 24f, fieldH, Theme.TEXT_SECONDARY);
        if (open) {
            owner.setDropdownOverlayBounds(x, fieldY + fieldH, w, fieldH);
        }
    }

    private record Fields(TextSetting petName, DropdownSetting maxLevel, DropdownSetting rarity,
                          TextSetting level1Price, TextSetting maxLevelPrice) {
    }

    private static final class Layout {
        private final float row1Y;
        private final float row2Y;
        private final float nameW;
        private final float quarterW;
        private final float nameX;
        private final float maxLevelX;
        private final float rarityX;
        private final float halfW;
        private final float level1X;
        private final float maxLevelX2;

        private Layout(float x, float y, float w) {
            float halfGap = MainGUI.petColGap() / 2f;
            row1Y = y + 6f;
            row2Y = row1Y + MainGUI.petFieldHeight() + MainGUI.petRowGap();
            nameW = (w - MainGUI.petColGap() * 2f) * 0.5f + halfGap;
            quarterW = (w - MainGUI.petColGap() * 2f - nameW) / 2f;
            nameX = x;
            maxLevelX = nameX + nameW + MainGUI.petColGap();
            rarityX = maxLevelX + quarterW + MainGUI.petColGap();
            halfW = (w - MainGUI.petColGap()) / 2f;
            level1X = x;
            maxLevelX2 = x + halfW + MainGUI.petColGap();
        }
    }
}
