package dev.aether.ui.gui.skin;

import dev.aether.ui.gui.Icon;

// the passive part of a group header handed to GuiSkin.groupHeader: SettingsList places the toggle, summary
// chip, header actions and peek chevron in the trailing strip, whose width is reserved here
public record GroupHeader(String title, String description, Icon icon, boolean enabled, boolean dimmed,
                          float trailingWidth, float hoverT) {
    public GroupHeader {
        title = title == null ? "" : title;
        description = description == null ? "" : description;
    }
}
