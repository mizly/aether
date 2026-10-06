package dev.aether.ui.settings;

import dev.aether.config.entries.ConfigEntry;

import java.util.List;

// implementations hold typed getter/setter references to AetherConfig entries
public interface Setting {
    String getName();
    default String getRawName() {
        return getName();
    }
    SettingType getType();
    default String getDescription() {
        return SettingDescriptionCatalog.describe(this);
    }
    boolean isVisible();

    // text the setting really has (describe(), a section line or a catalog entry); null when only the
    // generated fallback exists, so inline descriptions never repeat the label back
    default String explicitDescription() {
        return SettingDescriptionCatalog.explicit(this);
    }

    default List<ConfigEntry<?>> bindings() {
        return List.of();
    }

    // page id of this knob's canonical home when this copy is a mirror, otherwise null
    default String mirrorOf() {
        return null;
    }
}
