package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Rect;
import dev.aether.ui.settings.PlotSetting;

// the Configure Plots picker for one plot setting, opened from its inline widget
public record PlotRequest(Object anchorId, Rect anchor, PlotSetting setting) {
    public static PlotRequest of(Object anchorId, Rect anchor, PlotSetting setting) {
        return new PlotRequest(anchorId, anchor, setting);
    }
}
