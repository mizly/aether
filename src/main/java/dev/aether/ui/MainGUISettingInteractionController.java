package dev.aether.ui;

import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.DropdownListSetting;
import dev.aether.ui.settings.MultiDropdownSetting;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.RangeSliderSetting;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SettingType;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

final class MainGUISettingInteractionController {
    private final MainGUI owner;

    MainGUISettingInteractionController(MainGUI owner) {
        this.owner = owner;
    }

    void handleContentAreaClick(float mx, float my) {
        if (mx < owner.contentX() || mx > owner.contentX() + owner.contentW()
                || my < owner.contentY() || my > owner.contentY() + owner.contentH()) {
            return;
        }
        if (owner.searchResultsVisible()) {
            owner.handleSearchClickEvent(mx, my);
        } else if (owner.getActiveMainTab() == 0 && owner.getActiveModuleSubTab() != null) {
            handleModuleSettingsPanelClick(mx, my);
        } else if (owner.getActiveMainTab() == 1 || owner.getActiveMainTab() == 3 || owner.getActiveMainTab() == 4) {
            handleFlatContentClick(mx, my);
        }
    }

    void handleContentClick(float mx, float my) {
        owner.closeOpenDropdown();
        float gx = owner.contentX() + MainGUI.ITEM_PAD;
        float gw = owner.contentW() - MainGUI.ITEM_PAD * 2f;
        float y2 = 0f;

        for (SettingGroup group : owner.activeGroupsForInteraction()) {
            y2 += MainGUI.groupGap();
            float headerSY = owner.contentY() - owner.getActiveScrollY() + y2;

            if (!group.isAlwaysOn()) {
                float pillX = gx + gw - 52f;
                float pillY = headerSY + (MainGUI.HEADER_H - MainGUI.PILL_H) / 2f;
                if (mx >= pillX && mx <= pillX + 38f && my >= pillY && my <= pillY + MainGUI.PILL_H) {
                    group.toggle();
                    return;
                }
            }
            y2 += MainGUI.HEADER_H;

            if (owner.shouldShowChildren(group) && group.hasSettings()) {
                y2 += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                if (owner.isPetTrackerSettingsGroup(group)) {
                    float sh = owner.petTrackerGroupHeightValue();
                    float sSY = owner.contentY() - owner.getActiveScrollY() + y2;
                    if (sSY + sh > owner.contentY() && sSY < owner.contentY() + owner.contentH()
                            && my >= sSY && my <= sSY + sh
                            && owner.handlePetTrackerGroupInteraction(group, mx, my, gx, sSY, gw)) {
                        return;
                    }
                    y2 += sh;
                } else {
                    for (Setting setting : group.getSettings()) {
                        if (!setting.isVisible()) {
                            continue;
                        }
                        float sh = owner.settingHeightFor(setting, gw);
                        float sSY = owner.contentY() - owner.getActiveScrollY() + y2;
                        if (sSY + sh > owner.contentY() && sSY < owner.contentY() + owner.contentH()
                                && my >= sSY && my <= sSY + sh) {
                            handleSettingClick(setting, mx, my, gx, sSY, gw, sh);
                            return;
                        }
                        y2 += sh;
                    }
                }
                y2 += 4f;
            }
        }
    }

    void handleFlatContentClick(float mx, float my) {
        owner.closeOpenDropdown();
        float scrollTop = owner.contentScrollTop();
        float gx = owner.contentX() + MainGUI.ITEM_PAD;
        float gw = owner.contentW() - MainGUI.ITEM_PAD * 2f;
        float y2 = 0f;

        for (ModulesTab.SubTab subTab : owner.flatSubtabsForCurrentFilter()) {
            for (SettingGroup group : subTab.groups()) {
                y2 += 14f;
                float labelSY = scrollTop - owner.getActiveScrollY() + y2;

                if (!group.isAlwaysOn()) {
                    float pillX = gx + gw - 38f;
                    float pillY = labelSY + (MainGUI.FLAT_LABEL_H - MainGUI.PILL_H) / 2f;
                    if (mx >= pillX && mx <= pillX + 38f && my >= pillY && my <= pillY + MainGUI.PILL_H) {
                        group.toggle();
                        return;
                    }
                }
                y2 += MainGUI.FLAT_LABEL_H;

                if (owner.shouldShowChildren(group) && group.hasSettings()) {
                    y2 += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                    if (owner.isPetTrackerSettingsGroup(group)) {
                        float sh = owner.petTrackerGroupHeightValue();
                        float sSY = scrollTop - owner.getActiveScrollY() + y2;
                        if (my >= sSY && my <= sSY + sh && mx >= gx && mx <= gx + gw
                                && owner.handlePetTrackerGroupInteraction(group, mx, my, gx, sSY, gw)) {
                            return;
                        }
                        y2 += sh;
                    } else {
                        for (Setting setting : group.getSettings()) {
                            if (!setting.isVisible()) {
                                continue;
                            }
                            float sh = owner.settingHeightFor(setting, gw);
                            float sSY = scrollTop - owner.getActiveScrollY() + y2;
                            if (my >= sSY && my <= sSY + sh && mx >= gx && mx <= gx + gw) {
                                handleSettingClick(setting, mx, my, gx, sSY, gw, sh);
                                return;
                            }
                            y2 += sh;
                        }
                    }
                    y2 += 4f;
                }
            }
        }
    }

    void handleModuleSettingsPanelClick(float mx, float my) {
        owner.closeOpenDropdown();
        if (!owner.isActiveModuleSubtabEnabled()) {
            return;
        }

        float panelTop = owner.contentY() + MainGUI.TOP_BAR_H + 1f;
        float rx = owner.contentX() + MainGUI.MODULE_CAT_W + 1f;
        float rw = owner.contentW() - MainGUI.MODULE_CAT_W - 1f;
        float settTop = panelTop + MainGUI.MOD_HEADER_H + 1f;
        float gx = rx + MainGUI.ITEM_PAD;
        float gw = rw - MainGUI.ITEM_PAD * 2f;

        if (mx < rx) {
            return;
        }

        float y2 = 0f;
        for (SettingGroup group : owner.activeModuleClickGroups()) {
            y2 += 14f;
            y2 += MainGUI.HEADER_H;
            if (owner.shouldShowChildren(group) && group.hasSettings()) {
                y2 += MainGUI.HEADER_TO_FIRST_SETTING_GAP;
                if (owner.isPetTrackerSettingsGroup(group)) {
                    float sh = owner.petTrackerGroupHeightValue();
                    float sSY = settTop - owner.getActiveScrollY() + y2;
                    if (my >= sSY && my <= sSY + sh && mx >= gx && mx <= gx + gw
                            && owner.handlePetTrackerGroupInteraction(group, mx, my, gx, sSY, gw)) {
                        return;
                    }
                    y2 += sh;
                } else {
                    for (Setting setting : group.getSettings()) {
                        if (!setting.isVisible()) {
                            continue;
                        }
                        float sh = owner.settingHeightFor(setting, gw);
                        float sSY = settTop - owner.getActiveScrollY() + y2;
                        float cardH = sh - 5f;
                        if (my >= sSY && my <= sSY + cardH && mx >= gx && mx <= gx + gw) {
                            handleSettingClick(setting, mx, my, gx, sSY, gw, sh);
                            return;
                        }
                        y2 += sh;
                    }
                }
                y2 += 4f;
            }
        }
    }

    void handleSettingClick(Setting setting, float mx, float my, float x, float y, float w, float h) {
        if (setting.getType() != SettingType.TEXT && setting.getType() != SettingType.LIST
                && setting.getType() != SettingType.DROPDOWN_LIST) {
            owner.commitText();
        }
        if (setting.getType() != SettingType.COLOR) {
            owner.commitColor();
        }

        float ip = 16f;
        float ix = x + ip;
        float iw = w - ip * 2f;
        float cardH = h - 5f;

        switch (setting.getType()) {
            case TOGGLE -> ((ToggleSetting) setting).toggle();
            case SLIDER -> handleSliderClick((SliderSetting) setting, mx, my, y, ix, iw, cardH);
            case RANGE_SLIDER -> handleRangeSliderClick((RangeSliderSetting) setting, mx, my, y, ix, iw, cardH);
            case TEXT -> handleTextClick((TextSetting) setting, mx, my, y, ix, iw, cardH);
            case INFO -> { }
            case LIST -> handleListClick((ListSetting) setting, mx, my, y, w, ix, iw);
            case DROPDOWN_LIST -> handleDropdownListClick((DropdownListSetting) setting, mx, my, y, w, ix, iw);
            case DROPDOWN -> handleDropdownClick((DropdownSetting) setting, mx, my, y, ix, iw, cardH);
            case MULTI_DROPDOWN -> handleMultiDropdownClick((MultiDropdownSetting) setting, mx, my, y, ix, iw, cardH);
            case ACTION -> ((ActionSetting) setting).execute();
            case COLOR -> handleColorClick((ColorSetting) setting);
            case POSITION -> handlePositionClick((PositionSetting) setting, mx, my, y, ix, iw, cardH);
            case KEYBIND -> handleKeybindClick((KeybindSetting) setting, mx, my, y, ix, iw, cardH);
        }
    }

    boolean dragSlider(float mx) {
        if (!owner.hasDraggedSliderSetting()) {
            return false;
        }
        updateSlider(mx);
        return true;
    }

    boolean dragRangeSlider(float mx) {
        if (!owner.hasDraggedRangeSliderSetting()) {
            return false;
        }
        updateRangeSlider(mx);
        return true;
    }

    void updateSlider(float mx) {
        owner.applySliderDragValue(mx);
    }

    void updateRangeSlider(float mx) {
        owner.applyRangeSliderDragValue(mx);
    }

    private void handleSliderClick(SliderSetting setting, float mx, float my, float y, float ix, float iw, float cardH) {
        float sliderAreaW = iw / 3f;
        float sliderX = ix + iw - sliderAreaW;
        float valueBoxW = 66f;
        float valueBoxH = 32f;
        float valueBoxX = sliderX;
        float valueBoxY = y + (cardH - valueBoxH) / 2f;
        float trackX = sliderX + valueBoxW + MainGUI.SLIDER_FIELD_TRACK_GAP;
        float trackW = sliderAreaW - valueBoxW - MainGUI.SLIDER_FIELD_TRACK_GAP;
        float trackY = y + (cardH - 12f) / 2f;
        float trackH = 12f;

        if (mx >= valueBoxX && mx <= valueBoxX + valueBoxW && my >= valueBoxY && my <= valueBoxY + valueBoxH) {
            boolean sameActive = owner.isActiveSliderEditor(setting);
            String value = sameActive ? owner.currentTextBuffer() : owner.fmtSliderInput(setting);
            float textPad = 6f;
            float fieldTextSize = 12.5f;
            float startX = valueBoxX + (valueBoxW - value.length() * fieldTextSize * 0.52f) / 2f;
            owner.focusSliderEditorField(setting);
            owner.beginInlineFieldCaretPress(
                    valueBoxX,
                    valueBoxY,
                    valueBoxW,
                    valueBoxH,
                    value,
                    fieldTextSize,
                    startX,
                    mx,
                    my
            );
            return;
        }

        if (mx >= trackX && mx <= trackX + trackW && my >= trackY && my <= trackY + trackH) {
            owner.startSliderDrag(setting, trackX, trackW);
            updateSlider(mx);
        }
    }

    private void handleRangeSliderClick(RangeSliderSetting setting, float mx, float my, float y, float ix, float iw, float cardH) {
        float sliderAreaW = iw / 3f;
        float sliderX = ix + iw - sliderAreaW;
        float valueBoxW = 120f;
        float trackX = sliderX + valueBoxW + MainGUI.SLIDER_FIELD_TRACK_GAP;
        float trackW = sliderAreaW - valueBoxW - MainGUI.SLIDER_FIELD_TRACK_GAP;
        float trackY = y + (cardH - 12f) / 2f;
        float trackH = 12f;

        if (mx >= trackX && mx <= trackX + trackW && my >= trackY && my <= trackY + trackH) {
            owner.startRangeSliderDrag(setting, trackX, trackW, mx);
            updateRangeSlider(mx);
        }
    }

    private void handleTextClick(TextSetting setting, float mx, float my, float y, float ix, float iw, float cardH) {
        if (setting.isMultiline()) {
            owner.commitText();
            owner.activateTextSettingEditor(setting);
            float textY = y + 10f;
            float labelBlockH = owner.wrappedSettingLabelLineCountForRow(setting, iw + 32f) * owner.settingLabelLineStepForRow(setting);
            float fieldY = textY + labelBlockH + 6f;
            float fieldH = MainGUI.TEXT_MULTI_PAD_Y * 2f + setting.getVisibleLines() * MainGUI.TEXT_MULTI_LINE_STEP - 2f;
            owner.setEditorBounds(ix, fieldY, iw, fieldH);
            owner.selectAllText();
            return;
        }

        boolean sameActive = owner.isActiveTextEditor(setting);
        if (!sameActive) {
            owner.commitText();
            owner.activateTextSettingEditor(setting);
        }

        float fieldW = owner.textInlineFieldWidthForRow(iw);
        float fieldX = ix + iw - fieldW;
        float fieldY = y + (cardH - MainGUI.TEXT_INLINE_FIELD_H) / 2f;
        float textPad = 6f;
        float fieldTextSize = 12.5f;
        String value = owner.currentTextBuffer();
        float visibleTextW = fieldW - textPad * 2f;
        float textOffset = sameActive
                ? owner.inlineTextScrollOffsetApproxFor(value, owner.currentTextCursor(), fieldTextSize, visibleTextW)
                : 0f;
        float startX = fieldX + textPad - textOffset;
        owner.beginInlineFieldCaretPress(
                fieldX,
                fieldY,
                fieldW,
                MainGUI.TEXT_INLINE_FIELD_H,
                value,
                fieldTextSize,
                startX,
                mx,
                my
        );
    }

    private void handleListClick(ListSetting list, float mx, float my, float y, float rowW, float ix, float iw) {
        float textY = y + 9f;
        float labelBlockH = owner.wrappedSettingLabelLineCountForRow(list, rowW) * owner.settingLabelLineStepForRow(list);
        float listY = textY + labelBlockH + 6f;
        List<String> values = list.getValues();
        int rowCount = Math.max(1, values.size());
        float fieldW = iw - MainGUI.LIST_ACTION_W * 2f - MainGUI.LIST_ITEM_GAP * 2f;

        for (int i = 0; i < rowCount; i++) {
            boolean hasValue = i < values.size();
            boolean sameActive = owner.isActiveListEditor(list, i);
            float rowY = listY + i * (MainGUI.LIST_ITEM_H + MainGUI.LIST_ITEM_GAP);
            float plusX = ix + fieldW + MainGUI.LIST_ITEM_GAP;
            float minusX = plusX + MainGUI.LIST_ACTION_W + MainGUI.LIST_ITEM_GAP;

            if (mx >= plusX && mx <= plusX + MainGUI.LIST_ACTION_W && my >= rowY && my <= rowY + MainGUI.LIST_ITEM_H) {
                owner.commitText();
                owner.insertListEditorItem(list, hasValue ? i + 1 : 0);
                return;
            }
            if (hasValue && mx >= minusX && mx <= minusX + MainGUI.LIST_ACTION_W
                    && my >= rowY && my <= rowY + MainGUI.LIST_ITEM_H) {
                owner.commitText();
                owner.deleteListEditorItem(list, i);
                return;
            }
            if (mx >= ix && mx <= ix + fieldW && my >= rowY && my <= rowY + MainGUI.LIST_ITEM_H) {
                if (hasValue) {
                    if (!sameActive) {
                        owner.commitText();
                    }
                    owner.focusListEditorItem(list, i);
                    float textPad = 6f;
                    float fieldTextSize = 12.5f;
                    String value = owner.currentTextBuffer();
                    float visibleTextW = fieldW - textPad * 2f;
                    float textOffset = sameActive
                            ? owner.inlineTextScrollOffsetApproxFor(value, owner.currentTextCursor(), fieldTextSize, visibleTextW)
                            : 0f;
                    float startX = ix + textPad - textOffset;
                    owner.beginInlineFieldCaretPress(
                            ix,
                            rowY,
                            fieldW,
                            MainGUI.LIST_ITEM_H,
                            value,
                            fieldTextSize,
                            startX,
                            mx,
                            my
                    );
                } else {
                    owner.commitText();
                    owner.insertListEditorItem(list, 0);
                    owner.beginInlineFieldCaretPress(
                            ix,
                            rowY,
                            fieldW,
                            MainGUI.LIST_ITEM_H,
                            owner.currentTextBuffer(),
                            12.5f,
                            ix + 6f,
                            mx,
                            my
                    );
                }
                return;
            }
        }
    }

    private void handleDropdownListClick(DropdownListSetting setting, float mx, float my, float y, float w, float ix, float iw) {
        float textY = y + 9f;
        float labelBlockH = owner.wrappedSettingLabelLineCountForRow(setting, w) * owner.settingLabelLineStepForRow(setting);
        float rowY = textY + labelBlockH + 6f;
        float itemH  = MainGUI.LIST_ITEM_H;
        float gap    = MainGUI.LIST_ITEM_GAP;
        float actW   = MainGUI.LIST_ACTION_W;
        float itemFieldW = iw - actW * 3f - gap * 3f;

        List<String> values = setting.getValues();

        for (int i = 0; i < values.size(); i++) {
            float upX    = ix + itemFieldW + gap;
            float downX  = upX + actW + gap;
            float minusX = downX + actW + gap;

            if (mx >= upX && mx <= upX + actW && my >= rowY && my <= rowY + itemH && i > 0) {
                setting.moveUp(i);
                return;
            }
            if (mx >= downX && mx <= downX + actW && my >= rowY && my <= rowY + itemH && i < values.size() - 1) {
                setting.moveDown(i);
                return;
            }
            if (mx >= minusX && mx <= minusX + actW && my >= rowY && my <= rowY + itemH) {
                setting.removeValue(i);
                return;
            }
            rowY += itemH + gap;
        }

        // Add row
        DropdownSetting picker = setting.getAddPicker();
        float dropW = iw - actW - gap;
        float addX  = ix + dropW + gap;

        if (mx >= ix && mx <= ix + dropW && my >= rowY && my <= rowY + itemH) {
            if (owner.isOpenDropdown(picker)) {
                owner.closeOpenDropdown();
            } else {
                owner.openDropdownOverlayFor(picker, ix, rowY + itemH, dropW, itemH);
            }
            return;
        }
        if (mx >= addX && mx <= addX + actW && my >= rowY && my <= rowY + itemH) {
            owner.closeOpenDropdown();
            String selected = picker.getSelectedOption();
            if (selected != null && !selected.isBlank()) {
                setting.addValue(selected);
            }
        }
    }

    private void handleDropdownClick(DropdownSetting dropdown, float mx, float my, float y, float ix, float iw, float cardH) {
        float buttonH = MainGUI.DROPDOWN_FIELD_H;
        float buttonY = y + (cardH - buttonH) / 2f;
        float buttonX = ix + iw - MainGUI.DROPDOWN_FIELD_W;
        int actionIndex = owner.dropdownActionIndexFor(dropdown, buttonX, buttonY, buttonH, mx, my);
        if (actionIndex >= 0) {
            dropdown.getIconActions().get(actionIndex).execute();
            return;
        }
        if (owner.isOpenDropdown(dropdown)) {
            owner.closeOpenDropdown();
            return;
        }
        owner.openDropdownOverlayFor(dropdown, buttonX, buttonY + buttonH, MainGUI.DROPDOWN_FIELD_W, buttonH);
    }

    private void handleMultiDropdownClick(MultiDropdownSetting setting, float mx, float my, float y, float ix, float iw, float cardH) {
        float chipH    = MultiDropdownSetting.CHIP_H;
        float chipPadX = MultiDropdownSetting.CHIP_PAD_X;
        float chipGap  = MultiDropdownSetting.CHIP_GAP;
        float chipFontSz = MultiDropdownSetting.CHIP_FONT_SZ;
        float chipY = y + (cardH - chipH) / 2f;

        List<String> options = setting.getOptions();
        float[] chipWidths = new float[options.size()];
        float totalW = 0f;
        for (int i = 0; i < options.size(); i++) {
            chipWidths[i] = options.get(i).length() * (chipFontSz * 0.52f) + chipPadX * 2f;
            totalW += chipWidths[i];
            if (i > 0) totalW += chipGap;
        }

        float cx = ix + iw - totalW;
        for (int i = 0; i < options.size(); i++) {
            if (mx >= cx && mx <= cx + chipWidths[i] && my >= chipY && my <= chipY + chipH) {
                setting.toggleOption(i);
                return;
            }
            cx += chipWidths[i] + chipGap;
        }
    }

    private void handleColorClick(ColorSetting setting) {
        if (owner.isActiveColorSetting(setting)) {
            owner.commitColor();
            return;
        }
        owner.activateColorSetting(setting);
    }

    private void handlePositionClick(PositionSetting setting, float mx, float my, float y, float ix, float iw, float cardH) {
        float buttonH = 32f;
        float buttonY = y + (cardH - buttonH) / 2f;
        float captureW = 56f;
        float captureX = ix + iw - captureW;

        float valueGap = 6f;
        float valueW = 46f;
        float zX = captureX - valueGap - valueW;
        float yX = zX - valueGap - valueW;
        float xX = yX - valueGap - valueW;

        float pillW = 36f;
        float pillX = xX - valueGap - pillW - 10f;
        float midY = y + cardH / 2f;

        if (mx >= pillX && mx <= pillX + pillW && my >= midY - MainGUI.PILL_H / 2f && my <= midY + MainGUI.PILL_H / 2f) {
            setting.setHighlighted(!setting.isHighlighted());
            return;
        }
        if (mx >= captureX && mx <= captureX + captureW && my >= buttonY && my <= buttonY + buttonH) {
            setting.capture();
            return;
        }

        float[] boxX = {xX, yX, zX};
        double[] values = {setting.getX(), setting.getY(), setting.getZ()};
        for (int i = 0; i < 3; i++) {
            if (mx >= boxX[i] && mx <= boxX[i] + valueW && my >= buttonY && my <= buttonY + buttonH) {
                if (owner.isActivePositionEditor(setting, i)) {
                    return;
                }
                owner.commitText();
                owner.focusPositionEditorField(setting, i, values[i], mx, my);
                return;
            }
        }
    }

    private void handleKeybindClick(KeybindSetting setting, float mx, float my, float y, float ix, float iw, float cardH) {
        float buttonH = 32f;
        float buttonY = y + (cardH - buttonH) / 2f;
        float resetW = 60f;
        float bindW = 132f;
        float gap = 8f;
        float resetX = ix + iw - resetW;
        float bindX = resetX - gap - bindW;

        if (mx >= resetX && mx <= resetX + resetW && my >= buttonY && my <= buttonY + buttonH) {
            setting.resetToDefault();
            owner.clearKeybindCapture();
            return;
        }
        if (mx >= bindX && mx <= bindX + bindW && my >= buttonY && my <= buttonY + buttonH) {
            owner.beginKeybindCapture(setting);
        }
    }
}
