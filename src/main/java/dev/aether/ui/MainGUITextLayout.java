package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.DropdownListSetting;
import dev.aether.ui.settings.InfoSetting;
import dev.aether.ui.settings.MultiDropdownSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingType;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

final class MainGUITextLayout {
    float settingHeight(Setting setting, float rowW) {
        float extraLines = Math.max(0, wrappedSettingLabelLineCount(setting, rowW) - 1);
        return switch (setting.getType()) {
            case TEXT -> {
                TextSetting textSetting = (TextSetting) setting;
                if (!textSetting.isMultiline()) {
                    yield 50f + extraLines * 14f;
                }
                float labelBlockH = wrappedSettingLabelLineCount(setting, rowW) * settingLabelLineStep(setting);
                float fieldH = MainGUI.TEXT_MULTI_PAD_Y * 2f
                        + textSetting.getVisibleLines() * MainGUI.TEXT_MULTI_LINE_STEP - 2f;
                yield 26f + labelBlockH + fieldH;
            }
            case INFO -> {
                float labelBlockH = wrappedSettingLabelLineCount(setting, rowW) * settingLabelLineStep(setting);
                float valueFontSize = 12f;
                float valueLineStep = valueFontSize + 4f;
                int valueLines = 1;
                if (((InfoSetting) setting).isMultiline()) {
                    float innerWidth = Math.max(1f, rowW - 48f);
                    valueLines = estimateWrappedLineCount(((InfoSetting) setting).getValue(), innerWidth, valueFontSize);
                }
                yield 21f + labelBlockH + Math.max(28f, valueLines * valueLineStep + 12f);
            }
            case LIST -> {
                float labelBlockH = wrappedSettingLabelLineCount(setting, rowW) * settingLabelLineStep(setting);
                int rowCount = Math.max(1, ((ListSetting) setting).getValues().size());
                yield 9f + labelBlockH + 6f + rowCount * MainGUI.LIST_ITEM_H + (rowCount - 1) * MainGUI.LIST_ITEM_GAP + 15f;
            }
            case DROPDOWN_LIST -> {
                float labelBlockH = wrappedSettingLabelLineCount(setting, rowW) * settingLabelLineStep(setting);
                // existing items + 1 add row always visible
                int rowCount = ((DropdownListSetting) setting).getValues().size() + 1;
                yield 9f + labelBlockH + 6f + rowCount * MainGUI.LIST_ITEM_H + (rowCount - 1) * MainGUI.LIST_ITEM_GAP + 15f;
            }
            case ACTION -> 50f;
            default -> 50f + extraLines * 14f;
        };
    }

    int wrappedSettingLabelLineCount(Setting setting, float rowW) {
        return estimateWrappedLineCount(AetherLang.localize(setting.getName()), settingLabelMaxWidth(setting, rowW), settingLabelFontSize(setting));
    }

    float settingLabelFontSize(Setting setting) {
        return setting.getType() == SettingType.POSITION ? 13f : 13.5f;
    }

    float settingLabelLineStep(Setting setting) {
        return settingLabelFontSize(setting) + 3f;
    }

    float settingLabelMaxWidth(Setting setting, float rowW) {
        float innerWidth = Math.max(0f, rowW - 32f);
        return switch (setting.getType()) {
            case TOGGLE -> Math.max(80f, innerWidth - 50f);
            case SLIDER, RANGE_SLIDER -> Math.max(80f, innerWidth - (innerWidth / 3f) - 12f);
            case TEXT -> {
                TextSetting textSetting = (TextSetting) setting;
                yield textSetting.isMultiline()
                        ? Math.max(80f, innerWidth - 16f)
                        : Math.max(80f, innerWidth - textInlineFieldWidth(innerWidth) - MainGUI.TEXT_INLINE_FIELD_GAP);
            }
            case INFO -> Math.max(80f, innerWidth - 16f);
            case LIST, DROPDOWN_LIST -> Math.max(80f, innerWidth);
            case DROPDOWN -> Math.max(80f, innerWidth - (MainGUI.DROPDOWN_FIELD_W + dropdownActionStripWidth((DropdownSetting) setting) + 12f));
            case MULTI_DROPDOWN -> Math.max(80f, innerWidth - ((MultiDropdownSetting) setting).estimateTotalWidth() - 12f);
            case COLOR -> Math.max(80f, innerWidth - 96f);
            case POSITION -> Math.max(80f, innerWidth - 320f);
            case KEYBIND -> Math.max(80f, innerWidth - 220f);
            default -> Math.max(80f, innerWidth - 16f);
        };
    }

    float dropdownActionStripWidth(DropdownSetting dropdown) {
        int count = dropdown.getIconActions().size();
        if (count == 0) {
            return 0f;
        }
        return MainGUI.DROPDOWN_ACTION_GAP + count * MainGUI.DROPDOWN_ACTION_W + (count - 1) * MainGUI.DROPDOWN_ACTION_GAP;
    }

    float dropdownActionStartX(DropdownSetting dropdown, float fieldX) {
        return fieldX - dropdownActionStripWidth(dropdown);
    }

    int dropdownActionIndexAt(DropdownSetting dropdown, float fieldX, float fieldY, float fieldH, float mx, float my) {
        float startX = dropdownActionStartX(dropdown, fieldX);
        if (startX >= fieldX) {
            return -1;
        }

        for (int i = 0; i < dropdown.getIconActions().size(); i++) {
            float actionX = startX + i * (MainGUI.DROPDOWN_ACTION_W + MainGUI.DROPDOWN_ACTION_GAP);
            if (mx >= actionX && mx <= actionX + MainGUI.DROPDOWN_ACTION_W
                    && my >= fieldY && my <= fieldY + fieldH) {
                return i;
            }
        }
        return -1;
    }

    float textInlineFieldWidth(float innerWidth) {
        return Math.min(130f, innerWidth);
    }

    float inlineTextScrollOffset(NVGRenderer nvg, String font, String text, int cursor, float fontSize, float visibleWidth) {
        if (text == null || text.isEmpty() || visibleWidth <= 0f) {
            return 0f;
        }
        float totalWidth = nvg.textWidth(font, text, fontSize);
        if (totalWidth <= visibleWidth) {
            return 0f;
        }

        int safeCursor = Math.max(0, Math.min(cursor, text.length()));
        float cursorWidth = nvg.textWidth(font, text.substring(0, safeCursor), fontSize);
        float caretPadding = Math.min(18f, visibleWidth * 0.35f);
        float maxOffset = totalWidth - visibleWidth;
        return Math.max(0f, Math.min(maxOffset, cursorWidth - (visibleWidth - caretPadding)));
    }

    float inlineTextScrollOffsetApprox(String text, int cursor, float fontSize, float visibleWidth) {
        if (text == null || text.isEmpty() || visibleWidth <= 0f) {
            return 0f;
        }
        float avgCharWidth = fontSize * 0.52f;
        float totalWidth = text.length() * avgCharWidth;
        if (totalWidth <= visibleWidth) {
            return 0f;
        }

        int safeCursor = Math.max(0, Math.min(cursor, text.length()));
        float cursorWidth = safeCursor * avgCharWidth;
        float caretPadding = Math.min(18f, visibleWidth * 0.35f);
        float maxOffset = totalWidth - visibleWidth;
        return Math.max(0f, Math.min(maxOffset, cursorWidth - (visibleWidth - caretPadding)));
    }

    int estimateWrappedLineCount(String text, float maxWidth, float fontSize) {
        if (text == null || text.isBlank() || maxWidth <= 0f) {
            return 1;
        }

        float avgCharWidth = fontSize * 0.52f;
        int maxChars = Math.max(1, (int) Math.floor(maxWidth / avgCharWidth));
        int lines = 1;
        int current = 0;
        for (String word : text.trim().split("\\s+")) {
            int wordLength = word.length();
            if (current == 0) {
                current = wordLength;
                lines += Math.max(0, (wordLength - 1) / maxChars);
                current = ((wordLength - 1) % maxChars) + 1;
            } else if (current + 1 + wordLength <= maxChars) {
                current += 1 + wordLength;
            } else {
                lines++;
                lines += Math.max(0, (wordLength - 1) / maxChars);
                current = ((wordLength - 1) % maxChars) + 1;
            }
        }
        return Math.max(1, lines);
    }

    List<String> wrapSettingLabel(NVGRenderer nvg, Setting setting, float rowW) {
        return wrapTextToWidth(nvg, AetherLang.localize(setting.getName()), Fonts.REGULAR, settingLabelFontSize(setting), settingLabelMaxWidth(setting, rowW));
    }

    List<String> wrapTextToWidth(NVGRenderer nvg, String text, String fontName, float fontSize, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("");
            return lines;
        }
        if (maxWidth <= 0f) {
            lines.add(text);
            return lines;
        }

        String current = "";
        for (String word : text.trim().split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (current.isEmpty() || nvg.textWidth(fontName, candidate, fontSize) <= maxWidth) {
                current = candidate;
                continue;
            }

            lines.add(current);
            current = word;
            while (nvg.textWidth(fontName, current, fontSize) > maxWidth && current.length() > 1) {
                int split = fittingPrefixLength(nvg, current, fontName, fontSize, maxWidth);
                lines.add(current.substring(0, split));
                current = current.substring(split);
            }
        }

        if (!current.isEmpty()) {
            lines.add(current);
        }
        return lines.isEmpty() ? List.of(text) : lines;
    }

    int fittingPrefixLength(NVGRenderer nvg, String text, String fontName, float fontSize, float maxWidth) {
        int low = 1;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (nvg.textWidth(fontName, text.substring(0, mid), fontSize) <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return Math.max(1, low);
    }
}
