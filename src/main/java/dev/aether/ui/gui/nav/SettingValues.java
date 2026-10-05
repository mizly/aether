package dev.aether.ui.gui.nav;

import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownListSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.MultiDropdownSetting;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.ui.settings.RangeSliderSetting;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// a setting's value as a string the change log can store and write back for undo; null for types with no
// value (actions, info, sections) and for ones undo leaves alone (keybinds, plots)
public final class SettingValues {
    private SettingValues() {
    }

    public static String read(Setting setting) {
        return switch (setting) {
            case ToggleSetting toggle -> Boolean.toString(toggle.getValue());
            case SliderSetting slider -> number(slider.getValue());
            case RangeSliderSetting range -> number(range.getLowerValue()) + ";" + number(range.getUpperValue());
            case TextSetting text -> text.getValue();
            case DropdownSetting dropdown -> Integer.toString(dropdown.getSelectedIndex());
            case ColorSetting color -> String.format(Locale.ROOT, "%08X", color.getValue());
            case ListSetting list -> String.join("\n", list.getValues());
            case DropdownListSetting list -> String.join("\n", list.getValues());
            case MultiDropdownSetting multi -> selected(multi);
            case PositionSetting position -> position.getX() + ";" + position.getY() + ";" + position.getZ();
            default -> null;
        };
    }

    // false when the value no longer parses for this setting (the type or options changed since)
    public static boolean write(Setting setting, String value) {
        if (value == null) {
            return false;
        }
        try {
            switch (setting) {
                case ToggleSetting toggle -> toggle.setValue(Boolean.parseBoolean(value));
                case SliderSetting slider -> slider.setValue(Float.parseFloat(value));
                case RangeSliderSetting range -> {
                    String[] parts = value.split(";");
                    range.setValues(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]));
                }
                case TextSetting text -> text.setValue(value);
                case DropdownSetting dropdown -> {
                    int index = Integer.parseInt(value);
                    if (index < 0 || index >= dropdown.getOptions().size()) {
                        return false;
                    }
                    dropdown.setSelectedIndex(index);
                }
                case ColorSetting color -> color.setValue((int) Long.parseLong(value, 16));
                case ListSetting list -> list.setValues(lines(value));
                case DropdownListSetting list -> {
                    while (!list.getValues().isEmpty()) {
                        list.removeValue(list.getValues().size() - 1);
                    }
                    lines(value).forEach(list::addValue);
                }
                case MultiDropdownSetting multi -> {
                    Set<Integer> wanted = new HashSet<>();
                    for (String index : value.split(",")) {
                        if (!index.isBlank()) {
                            wanted.add(Integer.parseInt(index.trim()));
                        }
                    }
                    for (int i = 0; i < multi.getOptions().size(); i++) {
                        if (multi.isSelected(i) != wanted.contains(i)) {
                            multi.toggleOption(i);
                        }
                    }
                }
                case PositionSetting position -> {
                    String[] parts = value.split(";");
                    position.setX(Double.parseDouble(parts[0]));
                    position.setY(Double.parseDouble(parts[1]));
                    position.setZ(Double.parseDouble(parts[2]));
                }
                default -> {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // a stored value as the user reads it: On/Off, the option label, #AARRGGBB, "1 – 3"
    public static String display(Setting setting, String value) {
        if (value == null) {
            return "";
        }
        try {
            return switch (setting) {
                case ToggleSetting ignored -> AetherLang.localize(Boolean.parseBoolean(value) ? "On" : "Off");
                case RangeSliderSetting ignored -> value.replace(";", " – ");
                case DropdownSetting dropdown -> {
                    int index = Integer.parseInt(value);
                    yield index >= 0 && index < dropdown.getOptions().size() ? dropdown.getOptions().get(index) : value;
                }
                case ColorSetting ignored -> "#" + value;
                case ListSetting ignored -> String.join(", ", lines(value));
                case DropdownListSetting ignored -> String.join(", ", lines(value));
                case MultiDropdownSetting multi -> {
                    List<String> labels = new ArrayList<>();
                    for (String index : value.split(",")) {
                        if (!index.isBlank()) {
                            int i = Integer.parseInt(index.trim());
                            labels.add(i < multi.getOptions().size() ? multi.getOptions().get(i) : index);
                        }
                    }
                    yield String.join(", ", labels);
                }
                case PositionSetting ignored -> value.replace(";", ", ");
                default -> value;
            };
        } catch (RuntimeException e) {
            return value;
        }
    }

    private static String number(float value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e9f) {
            return Long.toString((long) value);
        }
        return Float.toString(value);
    }

    private static String selected(MultiDropdownSetting multi) {
        List<String> indices = new ArrayList<>();
        for (int i = 0; i < multi.getOptions().size(); i++) {
            if (multi.isSelected(i)) {
                indices.add(Integer.toString(i));
            }
        }
        return String.join(",", indices);
    }

    private static List<String> lines(String value) {
        return value.isEmpty() ? List.of() : Arrays.asList(value.split("\n", -1));
    }
}
