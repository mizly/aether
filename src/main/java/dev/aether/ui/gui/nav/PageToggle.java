package dev.aether.ui.gui.nav;

import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

// a page or block switch: the source subtab's own getter and setter (so their side effects survive), or a
// group's switch looked up live by raw name, since providers rebuild groups with new objects
public record PageToggle(Source source, String subTabRawName, String groupRawName, ModulesTab.ToggleKind kind,
                         BooleanSupplier getter, Consumer<Boolean> setter) {

    public enum Source { SUBTAB, GROUP }

    static PageToggle of(ModulesTab.SubTab subTab, ModulesTab.ToggleKind kind) {
        return new PageToggle(Source.SUBTAB, subTab.rawName(), null, kind == null ? subTab.toggleKind() : kind,
                subTab.enabledGetter(), subTab.enabledSetter());
    }

    static PageToggle ofGroup(ModulesTab.SubTab subTab, String groupRawName, ModulesTab.ToggleKind kind) {
        return new PageToggle(Source.GROUP, subTab.rawName(), groupRawName,
                kind == null ? ModulesTab.ToggleKind.FEATURE : kind,
                () -> {
                    SettingGroup group = find(subTab, groupRawName);
                    return group != null && group.isEnabled();
                },
                on -> {
                    SettingGroup group = find(subTab, groupRawName);
                    if (group != null) {
                        group.setEnabled(on);
                    }
                });
    }

    private static SettingGroup find(ModulesTab.SubTab subTab, String groupRawName) {
        for (SettingGroup group : subTab.groups()) {
            if (groupRawName.equals(group.getRawName())) {
                return group;
            }
        }
        return null;
    }

    public boolean isOn() {
        return getter.getAsBoolean();
    }

    public void set(boolean on) {
        setter.accept(on);
    }

    public void toggle() {
        set(!isOn());
    }

    public boolean runtime() {
        return kind == ModulesTab.ToggleKind.RUNTIME;
    }

    public boolean isGroup(SettingGroup group) {
        return source == Source.GROUP && groupRawName.equals(group.getRawName());
    }
}
