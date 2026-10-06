package dev.aether.ui.gui.widget;

import dev.aether.ui.gui.control.UiContext;
import dev.aether.ui.settings.Setting;

// draws one setting type as a row: label and description per the row layout, plus the controls that own
// the behaviour. heights are measured with real text metrics at the given width
public interface SettingWidget<S extends Setting> {
    float height(UiContext ui, S setting, Row row);

    void render(UiContext ui, S setting, Row row);

    // what a press anywhere on the row does (toggle, action, colour and dropdown rows), or null for nothing;
    // the row's own controls sit above it and take their presses first
    default Runnable rowAction(UiContext ui, S setting, Row row) {
        return null;
    }
}
