package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.DropdownListSetting;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.MultiDropdownSetting;
import dev.aether.ui.settings.KeybindSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.RangeSliderSetting;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

import java.util.List;

final class MainGUISettingRowRenderer {
    private final MainGUI owner;

    MainGUISettingRowRenderer(MainGUI owner) {
        this.owner = owner;
    }

    void render(NVGRenderer nvg, Setting setting, float x, float y, float w, float h, float mx, float my) {
        float cardH = h - 5f;

        nvg.roundedRect(x, y, w, cardH, 7f, Theme.CARD_BG);
        nvg.rectOutlineSolid(x, y, w, cardH, 7f, 1f, Theme.withAlpha(0xFFFFFFFF, 0.12f));

        float innerPad = 16f;
        float innerX = x + innerPad;
        float innerW = w - innerPad * 2f;
        float midY = y + cardH / 2f;
        List<String> labelLines = owner.wrapSettingLabelForRow(nvg, setting, w);
        float labelFontSize = owner.settingLabelFontSizeForRow(setting);
        float labelLineStep = owner.settingLabelLineStepForRow(setting);
        float labelBlockH = labelLines.size() * labelLineStep;

        switch (setting.getType()) {
            case TOGGLE -> renderToggle(nvg, (ToggleSetting) setting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH);
            case SLIDER -> renderSlider(nvg, (SliderSetting) setting, innerX, innerW, y, cardH, midY, labelLines, labelFontSize, labelLineStep, labelBlockH);
            case RANGE_SLIDER -> renderRangeSlider(nvg, (RangeSliderSetting) setting, innerX, innerW, y, cardH, midY, labelLines, labelFontSize, labelLineStep, labelBlockH);
            case TEXT -> renderText(nvg, (TextSetting) setting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH);
            case INFO -> renderInfo(nvg, (InfoSetting) setting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH);
            case LIST -> renderList(nvg, (ListSetting) setting, innerX, innerW, y, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my);
            case DROPDOWN_LIST -> renderDropdownList(nvg, (DropdownListSetting) setting, innerX, innerW, y, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my);
            case DROPDOWN -> renderDropdown(nvg, (DropdownSetting) setting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my);
            case MULTI_DROPDOWN -> renderMultiDropdown(nvg, (MultiDropdownSetting) setting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH);
            case ACTION -> renderAction(nvg, (ActionSetting) setting, innerX, innerW, y, cardH, mx, my);
            case COLOR -> renderColor(nvg, (ColorSetting) setting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my);
            case POSITION -> renderPosition(nvg, (PositionSetting) setting, innerX, innerW, y, cardH, midY, labelLines, labelFontSize, labelLineStep, labelBlockH, mx, my);
            case KEYBIND -> renderKeybind(nvg, (KeybindSetting) setting, innerX, innerW, y, cardH, labelLines, labelFontSize, labelLineStep, labelBlockH);
        }
    }

    private void renderToggle(NVGRenderer nvg, ToggleSetting setting, float x, float w, float y, float cardH,
                              List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH) {
        float textY = y + (cardH - labelBlockH) / 2f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }
        owner.renderPillControl(nvg, x + w - 38f, y + (cardH - MainGUI.PILL_H) / 2f, setting.getValue(), setting);
    }

    private void renderSlider(NVGRenderer nvg, SliderSetting setting, float x, float w, float y, float cardH, float midY,
                              List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH) {
        float textY = y + (cardH - labelBlockH) / 2f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }

        float sliderAreaW = w / 3f;
        float sliderX = x + w - sliderAreaW;
        float valueBoxW = 66f;
        float valueBoxH = 32f;
        float valueBoxX = sliderX;
        float valueBoxY = midY - valueBoxH / 2f;
        nvg.roundedRect(valueBoxX, valueBoxY, valueBoxW, valueBoxH, 7f, Theme.ELEMENT_BG);
        boolean active = owner.isActiveSliderEditor(setting);
        nvg.rectOutline(valueBoxX, valueBoxY, valueBoxW, valueBoxH, 7f, 1f,
                active ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
        if (active) {
            owner.setEditorBounds(valueBoxX, valueBoxY, valueBoxW, valueBoxH);
        }

        String valueText = active ? owner.currentTextBuffer() : owner.formatSliderValue(setting);
        float textPad = 6f;
        float fieldTextSize = 12.5f;
        float valueWidth = nvg.textWidth(Fonts.MONO, valueText, fieldTextSize);
        float drawX = valueBoxX + (valueBoxW - valueWidth) / 2f;
        float visibleTextW = valueBoxW - textPad * 2f;
        boolean clipped = owner.pushContentLocalScissor(nvg, valueBoxX + textPad, valueBoxY + 1f, visibleTextW, valueBoxH - 2f);
        if (active && owner.textHasSelection()) {
            int selStart = owner.currentTextSelectionStart();
            int selEnd = owner.currentTextSelectionEnd();
            float selX = drawX + nvg.textWidth(Fonts.MONO, owner.safeTextSlice(valueText, 0, selStart), fieldTextSize);
            float selW = nvg.textWidth(Fonts.MONO, owner.safeTextSlice(valueText, selStart, selEnd), fieldTextSize);
            nvg.roundedRect(selX, valueBoxY + 4f, Math.max(1f, selW), valueBoxH - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f));
        }
        if (valueText.isEmpty() && !active) {
            nvg.text(Fonts.MONO, "-", valueBoxX + textPad, valueBoxY + (valueBoxH - fieldTextSize) / 2f, fieldTextSize, Theme.TEXT_VALUE);
        } else {
            nvg.text(Fonts.MONO, valueText, drawX, valueBoxY + (valueBoxH - fieldTextSize) / 2f, fieldTextSize,
                    active ? Theme.TEXT_PRIMARY : Theme.TEXT_VALUE);
        }
        if (active && (System.currentTimeMillis() / 530) % 2 == 0) {
            String prefix = owner.safeTextSlice(valueText, 0, owner.currentTextCursor());
            float prefixW = nvg.textWidth(Fonts.MONO, prefix, fieldTextSize);
            nvg.rect(drawX + prefixW, valueBoxY + 4f, 1f, valueBoxH - 8f, Theme.TEXT_PRIMARY);
        }
        owner.popContentLocalScissor(nvg, clipped);

        float trackX = sliderX + valueBoxW + MainGUI.SLIDER_FIELD_TRACK_GAP;
        float trackW = sliderAreaW - valueBoxW - MainGUI.SLIDER_FIELD_TRACK_GAP;
        float fill = Math.max(0f, Math.min(1f,
                (setting.getValue() - setting.getMin()) / (setting.getMax() - setting.getMin())));

        nvg.roundedRect(trackX, midY - 2f, trackW, 4f, 2f, Theme.PILL_TRACK);
        if (fill > 0f) {
            nvg.horizontalGradient(trackX, midY - 2f, trackW * fill, 4f, 2f, Theme.SLIDER_LEFT, Theme.ACCENT_PRIMARY);
        }
        nvg.circle(trackX + trackW * fill, midY, 6f, 0xFFFFFFFF);
    }

    private void renderRangeSlider(NVGRenderer nvg, RangeSliderSetting setting, float x, float w, float y, float cardH, float midY,
                                   List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH) {
        float textY = y + (cardH - labelBlockH) / 2f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }

        float sliderAreaW = w / 3f;
        float sliderX = x + w - sliderAreaW;
        float valueBoxW = 120f;
        float valueBoxH = 32f;
        float valueBoxX = sliderX;
        float valueBoxY = midY - valueBoxH / 2f;
        nvg.roundedRect(valueBoxX, valueBoxY, valueBoxW, valueBoxH, 7f, Theme.ELEMENT_BG);
        nvg.rectOutline(valueBoxX, valueBoxY, valueBoxW, valueBoxH, 7f, 1f, Theme.BORDER_DEFAULT);
        String valueText = owner.formatRangeSliderValue(setting);
        float fieldTextSize = 12.5f;
        float valueWidth = nvg.textWidth(Fonts.MONO, valueText, fieldTextSize);
        nvg.text(Fonts.MONO, valueText, valueBoxX + (valueBoxW - valueWidth) / 2f, valueBoxY + (valueBoxH - fieldTextSize) / 2f,
                fieldTextSize, Theme.TEXT_VALUE);

        float trackX = sliderX + valueBoxW + MainGUI.SLIDER_FIELD_TRACK_GAP;
        float trackW = sliderAreaW - valueBoxW - MainGUI.SLIDER_FIELD_TRACK_GAP;
        float lowerFill = Math.max(0f, Math.min(1f,
                (setting.getLowerValue() - setting.getMin()) / (setting.getMax() - setting.getMin())));
        float upperFill = Math.max(0f, Math.min(1f,
                (setting.getUpperValue() - setting.getMin()) / (setting.getMax() - setting.getMin())));
        float lowerX = trackX + trackW * lowerFill;
        float upperX = trackX + trackW * upperFill;

        nvg.roundedRect(trackX, midY - 2f, trackW, 4f, 2f, Theme.PILL_TRACK);
        if (upperX > lowerX) {
            nvg.horizontalGradient(lowerX, midY - 2f, upperX - lowerX, 4f, 2f, Theme.SLIDER_LEFT, Theme.ACCENT_PRIMARY);
        }
        nvg.circle(lowerX, midY, 6f, 0xFFFFFFFF);
        nvg.circle(upperX, midY, 6f, 0xFFFFFFFF);
    }

    private void renderText(NVGRenderer nvg, TextSetting setting, float x, float w, float y, float cardH,
                            List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH) {
        boolean active = owner.isActiveTextEditor(setting);
        if (setting.isMultiline()) {
            renderMultilineText(nvg, setting, x, w, y, cardH, active, labelLines, labelFontSize, labelLineStep, labelBlockH);
            return;
        }

        float textY = y + (cardH - labelBlockH) / 2f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }

        float fieldW = owner.textInlineFieldWidthForRow(w);
        float fieldX = x + w - fieldW;
        float fieldY = y + (cardH - MainGUI.TEXT_INLINE_FIELD_H) / 2f;
        float fieldH = MainGUI.TEXT_INLINE_FIELD_H;
        nvg.roundedRect(fieldX, fieldY, fieldW, fieldH, 7f, Theme.BG_FIELD);
        nvg.rectOutline(fieldX, fieldY, fieldW, fieldH, 7f, 1f,
                active ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
        if (active) {
            owner.setEditorBounds(fieldX, fieldY, fieldW, fieldH);
        }

        float textPad = 6f;
        float fieldTextSize = 12.5f;
        String display = active ? owner.currentTextBuffer() : setting.getValue();
        float visibleTextW = fieldW - textPad * 2f;
        float textOffset = active
                ? owner.inlineTextScrollOffsetFor(nvg, Fonts.REGULAR, display, owner.currentTextCursor(), fieldTextSize, visibleTextW)
                : 0f;
        float drawX = fieldX + textPad - textOffset;
        boolean clipped = owner.pushContentLocalScissor(nvg, fieldX + textPad, fieldY + 1f, visibleTextW, fieldH - 2f);
        if (active && owner.textHasSelection()) {
            int selStart = owner.currentTextSelectionStart();
            int selEnd = owner.currentTextSelectionEnd();
            float selX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, 0, selStart), fieldTextSize);
            float selW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, selStart, selEnd), fieldTextSize);
            nvg.roundedRect(selX, fieldY + 4f, Math.max(1f, selW), fieldH - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f));
        }
        if (display.isEmpty() && !active) {
            nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getPlaceholder()), fieldX + textPad, fieldY + (fieldH - fieldTextSize) / 2f, fieldTextSize, Theme.TEXT_DIM);
        } else {
            nvg.text(Fonts.REGULAR, display, drawX, fieldY + (fieldH - fieldTextSize) / 2f, fieldTextSize, Theme.TEXT_PRIMARY);
        }
        if (active && (System.currentTimeMillis() / 530) % 2 == 0) {
            String prefix = owner.safeTextSlice(display, 0, owner.currentTextCursor());
            float caretX = drawX + nvg.textWidth(Fonts.REGULAR, prefix, fieldTextSize);
            nvg.rect(caretX, fieldY + 5f, 1f, fieldH - 10f, Theme.TEXT_PRIMARY);
        }
        owner.popContentLocalScissor(nvg, clipped);
    }

    private void renderInfo(NVGRenderer nvg, InfoSetting setting, float x, float w, float y, float cardH,
                            List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH) {
        float textY = y + 10f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }

        float valueY = textY + labelBlockH + 6f;
        float valueH = Math.max(24f, cardH - (valueY - y) - 10f);
        nvg.roundedRect(x, valueY, w, valueH, 7f, Theme.BG_FIELD);
        nvg.rectOutlineSolid(x, valueY, w, valueH, 7f, 1f, Theme.withAlpha(0xFFFFFFFF, 0.08f));

        String value = setting.getValue();
        float textPad = 8f;
        float fontSize = 12f;
        float lineStep = fontSize + 4f;
        float textMaxWidth = Math.max(1f, w - textPad * 2f);
        List<String> valueLines = setting.isMultiline()
                ? owner.wrapTextForWidth(nvg, value, Fonts.REGULAR, fontSize, textMaxWidth)
                : List.of(value);
        boolean clipped = owner.pushContentLocalScissor(nvg, x + textPad, valueY + 3f, textMaxWidth, valueH - 6f);
        for (int i = 0; i < valueLines.size(); i++) {
            nvg.text(Fonts.REGULAR, valueLines.get(i), x + textPad, valueY + 6f + i * lineStep, fontSize, Theme.TEXT_VALUE);
        }
        owner.popContentLocalScissor(nvg, clipped);
    }

    private void renderMultilineText(NVGRenderer nvg, TextSetting setting, float x, float w, float y, float cardH,
                                     boolean active, List<String> labelLines, float labelFontSize, float labelLineStep,
                                     float labelBlockH) {
        float textY = y + 10f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }

        float fieldY = textY + labelBlockH + 6f;
        float fieldH = MainGUI.TEXT_MULTI_PAD_Y * 2f + setting.getVisibleLines() * MainGUI.TEXT_MULTI_LINE_STEP - 2f;
        nvg.roundedRect(x, fieldY, w, fieldH, 7f, Theme.BG_FIELD);
        nvg.rectOutline(x, fieldY, w, fieldH, 7f, 1f,
                active ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
        if (active) {
            owner.setEditorBounds(x, fieldY, w, fieldH);
        }

        String display = active ? owner.currentTextBuffer() : setting.getValue();
        String[] lines = display.split("\n", -1);
        int startLine = active ? owner.getMultilineViewportStart(setting, display, owner.currentTextCursor()) : 0;
        int maxLines = Math.max(1, setting.getVisibleLines());
        float textPadX = 6f;
        float textPadY = MainGUI.TEXT_MULTI_PAD_Y;
        float fieldTextSize = 12f;
        float textMaxWidth = Math.max(1f, w - textPadX * 2f);
        boolean clipped = owner.pushContentLocalScissor(nvg, x + textPadX, fieldY + 1f, textMaxWidth, fieldH - 2f);

        for (int i = 0; i < maxLines; i++) {
            int lineIndex = startLine + i;
            if (lineIndex >= lines.length) {
                break;
            }
            nvg.text(
                    Fonts.REGULAR,
                    lines[lineIndex],
                    x + textPadX,
                    fieldY + textPadY + i * MainGUI.TEXT_MULTI_LINE_STEP,
                    fieldTextSize,
                    Theme.TEXT_PRIMARY);
        }

        if (display.isEmpty() && !active) {
            nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getPlaceholder()), x + textPadX, fieldY + textPadY, fieldTextSize, Theme.TEXT_DIM);
        }

        owner.popContentLocalScissor(nvg, clipped);
    }

    private void renderList(NVGRenderer nvg, ListSetting setting, float x, float w, float y,
                            List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH,
                            float mx, float my) {
        float textY = y + 9f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_PRIMARY);
        }

        List<String> values = setting.getValues();
        int rowCount = Math.max(1, values.size());
        float rowY = textY + labelBlockH + 6f;
        float fieldW = w - MainGUI.LIST_ACTION_W * 2f - MainGUI.LIST_ITEM_GAP * 2f;

        for (int i = 0; i < rowCount; i++) {
            boolean hasValue = i < values.size();
            boolean active = owner.isActiveListEditor(setting, i);
            String display = hasValue ? values.get(i) : "";
            float fieldTextSize = 12.5f;

            nvg.roundedRect(x, rowY, fieldW, MainGUI.LIST_ITEM_H, 7f, Theme.BG_FIELD);
            nvg.rectOutline(x, rowY, fieldW, MainGUI.LIST_ITEM_H, 7f, 1f,
                    active ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
            if (active) {
                owner.setEditorBounds(x, rowY, fieldW, MainGUI.LIST_ITEM_H);
            }

            float textPad = 6f;
            float visibleTextW = fieldW - textPad * 2f;
            float textOffset = active
                    ? owner.inlineTextScrollOffsetFor(nvg, Fonts.REGULAR, owner.currentTextBuffer(), owner.currentTextCursor(), fieldTextSize, visibleTextW)
                    : 0f;
            float drawX = x + textPad - textOffset;
            boolean clipped = owner.pushContentLocalScissor(nvg, x + textPad, rowY + 1f, visibleTextW, MainGUI.LIST_ITEM_H - 2f);
            if (active) {
                int selStart = owner.currentTextSelectionStart();
                int selEnd = owner.currentTextSelectionEnd();
                if (owner.textHasSelection()) {
                    float selX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(owner.currentTextBuffer(), 0, selStart), fieldTextSize);
                    float selW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(owner.currentTextBuffer(), selStart, selEnd), fieldTextSize);
                    nvg.roundedRect(selX, rowY + 4f, Math.max(1f, selW), MainGUI.LIST_ITEM_H - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f));
                }
                nvg.text(Fonts.REGULAR, owner.currentTextBuffer(), drawX,
                        rowY + (MainGUI.LIST_ITEM_H - fieldTextSize) / 2f, fieldTextSize, Theme.TEXT_PRIMARY);
                if ((System.currentTimeMillis() / 530) % 2 == 0) {
                    String prefix = owner.safeTextSlice(owner.currentTextBuffer(), 0, owner.currentTextCursor());
                    float caretX = drawX + nvg.textWidth(Fonts.REGULAR, prefix, fieldTextSize);
                    nvg.rect(caretX, rowY + 5f, 1f, MainGUI.LIST_ITEM_H - 10f, Theme.TEXT_PRIMARY);
                }
            } else if (display.isEmpty()) {
                nvg.text(Fonts.REGULAR, AetherLang.localize(setting.getPlaceholder()), x + textPad,
                        rowY + (MainGUI.LIST_ITEM_H - fieldTextSize) / 2f, fieldTextSize, Theme.TEXT_DIM);
            } else {
                nvg.text(Fonts.REGULAR, display, x + textPad,
                        rowY + (MainGUI.LIST_ITEM_H - fieldTextSize) / 2f, fieldTextSize, Theme.TEXT_PRIMARY);
            }
            owner.popContentLocalScissor(nvg, clipped);

            float plusX = x + fieldW + MainGUI.LIST_ITEM_GAP;
            float minusX = plusX + MainGUI.LIST_ACTION_W + MainGUI.LIST_ITEM_GAP;
            owner.renderListActionButtonControl(nvg, plusX, rowY, MainGUI.LIST_ACTION_W, "+",
                    mx >= plusX && mx <= plusX + MainGUI.LIST_ACTION_W && my >= rowY && my <= rowY + MainGUI.LIST_ITEM_H,
                    true);
            owner.renderListActionButtonControl(nvg, minusX, rowY, MainGUI.LIST_ACTION_W, "-",
                    mx >= minusX && mx <= minusX + MainGUI.LIST_ACTION_W && my >= rowY && my <= rowY + MainGUI.LIST_ITEM_H,
                    hasValue);

            rowY += MainGUI.LIST_ITEM_H + MainGUI.LIST_ITEM_GAP;
        }
    }

    private void renderDropdownList(NVGRenderer nvg, DropdownListSetting setting, float x, float w, float y,
                                    List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH,
                                    float mx, float my) {
        float textY = y + 9f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_PRIMARY);
        }

        List<String> values = setting.getValues();
        float rowY = textY + labelBlockH + 6f;
        float itemH  = MainGUI.LIST_ITEM_H;
        float gap    = MainGUI.LIST_ITEM_GAP;
        float actW   = MainGUI.LIST_ACTION_W;
        float fieldTextSize = 12.5f;
        float textPad = 6f;

        // 3 action buttons (↑ ↓ −) per existing item
        float itemFieldW = w - actW * 3f - gap * 3f;

        for (int i = 0; i < values.size(); i++) {
            String display = (i + 1) + ".  " + values.get(i);
            nvg.roundedRect(x, rowY, itemFieldW, itemH, 7f, Theme.BG_FIELD);
            nvg.rectOutline(x, rowY, itemFieldW, itemH, 7f, 1f, Theme.BORDER_DEFAULT);
            boolean clipped = owner.pushContentLocalScissor(nvg, x + textPad, rowY + 1f, itemFieldW - textPad * 2f, itemH - 2f);
            nvg.text(Fonts.REGULAR, display, x + textPad, rowY + (itemH - fieldTextSize) / 2f, fieldTextSize, Theme.TEXT_PRIMARY);
            owner.popContentLocalScissor(nvg, clipped);

            float upX    = x + itemFieldW + gap;
            float downX  = upX + actW + gap;
            float minusX = downX + actW + gap;

            owner.renderListActionButtonControl(nvg, upX,    rowY, actW, "↑",
                    mx >= upX    && mx <= upX    + actW && my >= rowY && my <= rowY + itemH, i > 0);
            owner.renderListActionButtonControl(nvg, downX,  rowY, actW, "↓",
                    mx >= downX  && mx <= downX  + actW && my >= rowY && my <= rowY + itemH, i < values.size() - 1);
            owner.renderListActionButtonControl(nvg, minusX, rowY, actW, "−",
                    mx >= minusX && mx <= minusX + actW && my >= rowY && my <= rowY + itemH, true);

            rowY += itemH + gap;
        }

        // Add row: dropdown picker (full width minus one action button) + "+" button
        DropdownSetting picker = setting.getAddPicker();
        float dropW = w - actW - gap;
        boolean open = owner.isOpenDropdown(picker);

        nvg.roundedRect(x, rowY, dropW, itemH, 7f, Theme.BG_FIELD);
        nvg.rectOutline(x, rowY, dropW, itemH, 7f, 1f, open ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
        boolean clippedDrop = owner.pushContentLocalScissor(nvg, x + textPad, rowY + 1f, dropW - 30f - textPad, itemH - 2f);
        nvg.text(Fonts.REGULAR, picker.getSelectedOption(), x + textPad, rowY + (itemH - fieldTextSize) / 2f, fieldTextSize, Theme.TEXT_PRIMARY);
        owner.popContentLocalScissor(nvg, clippedDrop);
        nvg.rect(x + dropW - 30f, rowY + 6f, 1f, itemH - 12f, Theme.SEPARATOR);
        DropdownChevron.renderDown(nvg, x + dropW - 30f, rowY, 30f, itemH, Theme.TEXT_MUTED);
        if (open) {
            owner.setDropdownOverlayBounds(x, rowY + itemH, dropW, itemH);
        }

        float addX = x + dropW + gap;
        owner.renderListActionButtonControl(nvg, addX, rowY, actW, "+",
                mx >= addX && mx <= addX + actW && my >= rowY && my <= rowY + itemH, true);
    }

    private void renderDropdown(NVGRenderer nvg, DropdownSetting setting, float x, float w, float y, float cardH,
                                List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH,
                                float mx, float my) {
        float textY = y + (cardH - labelBlockH) / 2f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }
        float boxW = MainGUI.DROPDOWN_FIELD_W;
        float boxH = MainGUI.DROPDOWN_FIELD_H;
        float boxX = x + w - boxW;
        float boxY = y + (cardH - boxH) / 2f;
        boolean open = owner.isOpenDropdown(setting);
        owner.renderDropdownActionButtonsControl(nvg, setting, boxX, boxY, boxH, mx, my);
        nvg.roundedRect(boxX, boxY, boxW, boxH, 7f, Theme.BG_FIELD);
        nvg.rectOutline(boxX, boxY, boxW, boxH, 7f, 1f,
                open ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
        float textPad = 6f;
        float fieldTextSize = 12.5f;
        boolean clipped = owner.pushContentLocalScissor(nvg, boxX + textPad, boxY + 1f, boxW - 30f - textPad, boxH - 2f);
        nvg.text(Fonts.REGULAR, setting.getSelectedOption(), boxX + textPad,
                boxY + (boxH - fieldTextSize) / 2f, fieldTextSize, Theme.TEXT_PRIMARY);
        owner.popContentLocalScissor(nvg, clipped);
        nvg.rect(boxX + boxW - 30f, boxY + 6f, 1f, boxH - 12f, Theme.SEPARATOR);
        DropdownChevron.renderDown(nvg, boxX + boxW - 30f, boxY, 30f, boxH, Theme.TEXT_MUTED);
        if (open) {
            owner.setDropdownOverlayBounds(boxX, boxY + boxH, boxW, boxH);
        }
    }

    private void renderAction(NVGRenderer nvg, ActionSetting setting, float x, float w, float y, float cardH, float mx, float my) {
        float buttonH = 32f;
        float buttonY = y + (cardH - buttonH) / 2f;
        boolean hovered = mx >= x && mx <= x + w && my >= buttonY && my <= buttonY + buttonH;
        int buttonBg = hovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) : Theme.ELEMENT_BG;
        int buttonBorder = hovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) : Theme.withAlpha(0xFFFFFFFF, 0.06f);
        int buttonText = hovered ? Theme.ACCENT_PRIMARY : Theme.TEXT_VALUE;
        nvg.roundedRect(x, buttonY, w, buttonH, 7f, buttonBg);
        nvg.rectOutline(x, buttonY, w, buttonH, 7f, 1f, buttonBorder);
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize(setting.getName()), x, buttonY, w, buttonH, 12.5f, buttonText);
    }

    private void renderColor(NVGRenderer nvg, ColorSetting setting, float x, float w, float y, float cardH,
                             List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH,
                             float mx, float my) {
        float textY = y + (cardH - labelBlockH) / 2f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }
        float swatchW = 24f;
        float swatchH = 24f;
        float swatchX = x + w - swatchW;
        float swatchY = y + (cardH - swatchH) / 2f;
        String hex = String.format("#%06X", setting.getValue() & 0xFFFFFF);
        float hexW = nvg.textWidth(Fonts.MONO, hex, 11f);
        nvg.text(Fonts.MONO, hex, swatchX - hexW - 7f, swatchY + (swatchH - 11f) / 2f, 11f, Theme.TEXT_MUTED);
        boolean active = owner.isActiveColorSetting(setting);
        if (mx >= swatchX && mx <= swatchX + swatchW && my >= swatchY && my <= swatchY + swatchH) {
            owner.setHoveredColorSetting(setting);
        }
        nvg.roundedRect(swatchX, swatchY, swatchW, swatchH, 5f, setting.getValue() | 0xFF000000);
        nvg.rectOutline(swatchX, swatchY, swatchW, swatchH, 5f, 1f,
                active ? Theme.ACCENT_PRIMARY : Theme.withAlpha(0xFFFFFFFF, 0.12f));
    }

    private void renderPosition(NVGRenderer nvg, PositionSetting setting, float x, float w, float y, float cardH, float midY,
                                List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH,
                                float mx, float my) {
        float textY = y + (cardH - labelBlockH) / 2f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_PRIMARY);
        }

        float boxH = 32f;
        float boxY = y + (cardH - boxH) / 2f;
        float captureW = 56f;
        float actionGap = MainGUI.LIST_ITEM_GAP;
        float actionW = MainGUI.LIST_ACTION_W;
        List<PositionSetting.ActionButton> actions = setting.getActionButtons();
        float actionStripW = actions.isEmpty() ? 0f : actions.size() * actionW + actions.size() * actionGap;
        float captureX = x + w - actionStripW - captureW;

        float pillW = 36f;
        float pillH = MainGUI.PILL_H;
        float valueGap = 6f;
        float valueW = 46f;
        float zX = captureX - valueGap - valueW;
        float yX = zX - valueGap - valueW;
        float xX = yX - valueGap - valueW;
        float pillX = xX - valueGap - pillW - 10f;
        float highlightW = 52f;

        nvg.textRight(Fonts.REGULAR, AetherLang.localize("Highlight"), pillX - highlightW - 8f, midY - labelFontSize / 2f,
                highlightW, labelFontSize, Theme.TEXT_PRIMARY);
        owner.renderPillControl(nvg, pillX, midY - pillH / 2f, setting.isHighlighted(), setting);

        float[] boxX = {xX, yX, zX};
        double[] values = {setting.getX(), setting.getY(), setting.getZ()};
        for (int i = 0; i < 3; i++) {
            boolean active = owner.isActivePositionEditor(setting, i);
            nvg.roundedRect(boxX[i], boxY, valueW, boxH, 7f, Theme.BG_FIELD);
            nvg.rectOutline(boxX[i], boxY, valueW, boxH, 7f, 1f,
                    active ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
            if (active) {
                owner.setEditorBounds(boxX[i], boxY, valueW, boxH);
            }

            String display = active ? owner.currentTextBuffer() : String.format("%.1f", values[i]);
            float textPad = 6f;
            float fieldTextSize = 12.5f;
            float visibleTextW = valueW - textPad * 2f;
            float textOffset = active
                    ? owner.inlineTextScrollOffsetApproxFor(display, owner.currentTextCursor(), fieldTextSize, visibleTextW)
                    : 0f;
            float drawX = boxX[i] + textPad - textOffset;
            boolean clipped = owner.pushContentLocalScissor(nvg, boxX[i] + textPad, boxY + 1f, visibleTextW, boxH - 2f);
            if (active && owner.textHasSelection()) {
                int selStart = owner.currentTextSelectionStart();
                int selEnd = owner.currentTextSelectionEnd();
                float selX = drawX + nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, 0, selStart), fieldTextSize);
                float selW = nvg.textWidth(Fonts.REGULAR, owner.safeTextSlice(display, selStart, selEnd), fieldTextSize);
                nvg.roundedRect(selX, boxY + 4f, Math.max(1f, selW), boxH - 8f, 3f, Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f));
            }
            nvg.text(Fonts.REGULAR, display, drawX, boxY + (boxH - fieldTextSize) / 2f, fieldTextSize,
                    active ? Theme.TEXT_PRIMARY : Theme.TEXT_SECONDARY);
            if (active && (System.currentTimeMillis() / 530) % 2 == 0) {
                String prefix = owner.safeTextSlice(display, 0, owner.currentTextCursor());
                float offset = nvg.textWidth(Fonts.REGULAR, prefix, fieldTextSize);
                nvg.rect(drawX + offset, boxY + 4f, 1f, boxH - 8f, Theme.TEXT_PRIMARY);
            }
            owner.popContentLocalScissor(nvg, clipped);
        }

        boolean hovered = mx >= captureX && mx <= captureX + captureW && my >= boxY && my <= boxY + boxH;
        int buttonBg = hovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) : Theme.ELEMENT_BG;
        int buttonBorder = hovered ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) : Theme.withAlpha(0xFFFFFFFF, 0.06f);
        int buttonText = hovered ? Theme.ACCENT_PRIMARY : Theme.TEXT_VALUE;
        nvg.roundedRect(captureX, boxY, captureW, boxH, 7f, buttonBg);
        nvg.rectOutline(captureX, boxY, captureW, boxH, 7f, 1f, buttonBorder);
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Capture"), captureX, boxY, captureW, boxH, 11.5f, buttonText);

        float actionX = captureX + captureW + actionGap;
        for (PositionSetting.ActionButton action : actions) {
            boolean actionHovered = mx >= actionX && mx <= actionX + actionW && my >= boxY && my <= boxY + boxH;
            owner.renderListActionButtonControl(nvg, actionX, boxY, actionW, action.label(), actionHovered, action.isEnabled());
            actionX += actionW + actionGap;
        }
    }

    private void renderMultiDropdown(NVGRenderer nvg, MultiDropdownSetting setting, float x, float w, float y, float cardH,
                                     List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH) {
        float textY = y + (cardH - labelBlockH) / 2f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }
        float chipH      = MultiDropdownSetting.CHIP_H;
        float chipPadX   = MultiDropdownSetting.CHIP_PAD_X;
        float chipGap    = MultiDropdownSetting.CHIP_GAP;
        float chipFontSz = MultiDropdownSetting.CHIP_FONT_SZ;
        float chipY = y + (cardH - chipH) / 2f;

        List<String> options = setting.getOptions();
        float[] chipWidths = new float[options.size()];
        float totalW = 0f;
        for (int i = 0; i < options.size(); i++) {
            String optionText = AetherLang.localize(options.get(i));
            chipWidths[i] = nvg.textWidth(Fonts.REGULAR, optionText, chipFontSz) + chipPadX * 2f;
            totalW += chipWidths[i];
            if (i > 0) totalW += chipGap;
        }

        float cx = x + w - totalW;
        for (int i = 0; i < options.size(); i++) {
            boolean sel = setting.isSelected(i);
            int bg        = sel ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) : Theme.ELEMENT_BG;
            int border    = sel ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.5f)  : Theme.BORDER_DEFAULT;
            int textColor = sel ? Theme.ACCENT_PRIMARY : Theme.TEXT_VALUE;
            nvg.roundedRect(cx, chipY, chipWidths[i], chipH, 5f, bg);
            nvg.rectOutline(cx, chipY, chipWidths[i], chipH, 5f, 1f, border);
            nvg.textCentered(Fonts.REGULAR, AetherLang.localize(options.get(i)), cx, chipY, chipWidths[i], chipH, chipFontSz, textColor);
            cx += chipWidths[i] + chipGap;
        }
    }

    private void renderKeybind(NVGRenderer nvg, KeybindSetting setting, float x, float w, float y, float cardH,
                               List<String> labelLines, float labelFontSize, float labelLineStep, float labelBlockH) {
        float textY = y + (cardH - labelBlockH) / 2f;
        for (int i = 0; i < labelLines.size(); i++) {
            nvg.text(Fonts.REGULAR, labelLines.get(i), x, textY + i * labelLineStep, labelFontSize, Theme.TEXT_LABEL);
        }

        float buttonH = 32f;
        float buttonY = y + (cardH - buttonH) / 2f;
        float resetW = 60f;
        float bindW = 132f;
        float gap = 8f;
        float resetX = x + w - resetW;
        float bindX = resetX - gap - bindW;
        boolean listening = owner.isAwaitingKeybindCapture(setting);

        int bindBg = listening ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.16f) : Theme.BG_FIELD;
        int bindBorder = listening ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT;
        int bindText = listening ? Theme.ACCENT_PRIMARY : Theme.TEXT_VALUE;
        nvg.roundedRect(bindX, buttonY, bindW, buttonH, 7f, bindBg);
        nvg.rectOutline(bindX, buttonY, bindW, buttonH, 7f, 1f, bindBorder);

        String bindTextValue = listening ? AetherLang.localize("Press a key...") : setting.getBoundKeyName();
        float textPad = 8f;
        boolean clipped = owner.pushContentLocalScissor(nvg, bindX + textPad, buttonY + 1f, bindW - textPad * 2f, buttonH - 2f);
        nvg.textCentered(Fonts.REGULAR, bindTextValue, bindX + textPad, buttonY, bindW - textPad * 2f, buttonH, 12f, bindText);
        owner.popContentLocalScissor(nvg, clipped);

        int resetBg = Theme.ELEMENT_BG;
        int resetBorder = setting.isDefault() ? Theme.BORDER_DEFAULT : Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.28f);
        int resetText = setting.isDefault() ? Theme.TEXT_MUTED : Theme.TEXT_VALUE;
        nvg.roundedRect(resetX, buttonY, resetW, buttonH, 7f, resetBg);
        nvg.rectOutline(resetX, buttonY, resetW, buttonH, 7f, 1f, resetBorder);
        nvg.textCentered(Fonts.REGULAR, AetherLang.localize("Reset"), resetX, buttonY, resetW, buttonH, 12f, resetText);
    }
}
