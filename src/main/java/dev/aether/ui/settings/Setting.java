package dev.aether.ui.settings;

/**
 * Base interface for all configurable settings in the ClickGUI.
 * Implementations hold typed getter/setter references to AetherConfig fields.
 */
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
}
