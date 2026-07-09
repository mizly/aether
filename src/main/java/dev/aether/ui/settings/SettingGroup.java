package dev.aether.ui.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import dev.aether.util.AetherLang;

/**
 * Represents a named group of settings (analogous to a Module) that can be
 * enabled/disabled and expanded in the GUI.
 *
 * Example:
 *   SettingGroup.of("Auto Visitor", "Automates visitor interactions",
 *       () -> AetherConfig.autoVisitor,
 *       v -> { AetherConfig.autoVisitor = v; AetherConfig.save(); })
 *     .add(new SliderSetting("Threshold", 1, 20, ...))
 *     .add(new ToggleSetting("Swap Armor", ...));
 */
public class SettingGroup {

    private final String name;
    private final String rawName;
    private final String description;
    private final String rawDescription;
    private final BooleanSupplier enabledGetter;
    private final Consumer<Boolean> enabledSetter;
    private final boolean alwaysOnFlag;
    private final List<Setting> settings = new ArrayList<>();

    private SettingGroup(String name, String description,
                         BooleanSupplier enabledGetter, Consumer<Boolean> enabledSetter,
                         boolean alwaysOnFlag) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.rawDescription = description;
        this.description = AetherLang.localize(description);
        this.enabledGetter = enabledGetter;
        this.enabledSetter = enabledSetter;
        this.alwaysOnFlag = alwaysOnFlag;
    }

    public static SettingGroup of(String name, String description,
                                   BooleanSupplier enabledGetter, Consumer<Boolean> enabledSetter) {
        return new SettingGroup(name, description, enabledGetter, enabledSetter, false);
    }

    /** For groups with no enable/disable (always visible in UI, no toggle). */
    public static SettingGroup alwaysOn(String name, String description) {
        return new SettingGroup(name, description, () -> true, v -> {}, true);
    }

    public SettingGroup add(Setting setting) {
        settings.add(setting);
        return this;
    }

    public boolean isEnabled() { return enabledGetter.getAsBoolean(); }
    public void toggle() { enabledSetter.accept(!isEnabled()); }
    public void setEnabled(boolean value) { enabledSetter.accept(value); }

    public String getName() { return name; }
    public String getRawName() { return rawName; }
    public String getDescription() { return description; }
    public String getRawDescription() { return rawDescription; }
    public List<Setting> getSettings() { return settings; }
    public boolean hasSettings() { return !settings.isEmpty(); }
    public boolean isAlwaysOn() { return alwaysOnFlag; }
}
