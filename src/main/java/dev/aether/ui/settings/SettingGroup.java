package dev.aether.ui.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.ui.gui.Icon;
import dev.aether.util.AetherLang;

// a named group of settings that can be toggled and expanded, roughly one module
public class SettingGroup {

    private final String name;
    private final String rawName;
    private final String description;
    private final String rawDescription;
    private final BooleanSupplier enabledGetter;
    private final Consumer<Boolean> enabledSetter;
    private final boolean alwaysOnFlag;
    private final List<Setting> settings = new ArrayList<>();
    private final List<HeaderAction> headerActions = new ArrayList<>();
    private Icon icon;
    private String marker;
    private Supplier<String> summary;
    private String mirrorOf;

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

    // no enable/disable toggle; always visible
    public static SettingGroup alwaysOn(String name, String description) {
        return new SettingGroup(name, description, () -> true, v -> {}, true);
    }

    public SettingGroup add(Setting setting) {
        settings.add(setting);
        return this;
    }

    public SettingGroup icon(Icon icon) {
        this.icon = icon;
        return this;
    }

    // picks a custom group renderer, like the compact pet tracker form; set it on every rebuilt group
    public SettingGroup marker(String marker) {
        this.marker = marker;
        return this;
    }

    // buttons every style draws in the group header, such as Add Pet and Remove Pet
    public SettingGroup headerAction(HeaderAction action) {
        headerActions.add(action);
        return this;
    }

    // a short chip beside the title, e.g. the action and delay of a failsafe, read every frame
    public SettingGroup summary(Supplier<String> summary) {
        this.summary = summary;
        return this;
    }

    // marks a copy of a group whose canonical home is another page
    public SettingGroup mirrorOf(String pageId) {
        this.mirrorOf = pageId;
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
    public Icon icon() { return icon; }
    public String marker() { return marker; }
    public List<HeaderAction> headerActions() { return Collections.unmodifiableList(headerActions); }
    public String mirrorOf() { return mirrorOf; }

    // the chip text right now, or null when the group has none
    public String summary() {
        return summary == null ? null : summary.get();
    }

    // label is raw english; visible and enabled are read every frame and may be null for always
    public record HeaderAction(String rawLabel, Icon icon, Runnable action, BooleanSupplier visible,
                               BooleanSupplier enabled) {
        public String label() {
            return AetherLang.localize(rawLabel);
        }

        public boolean isVisible() {
            return visible == null || visible.getAsBoolean();
        }

        public boolean isEnabled() {
            return enabled == null || enabled.getAsBoolean();
        }

        public void run() {
            if (isVisible() && isEnabled() && action != null) {
                action.run();
            }
        }
    }
}
