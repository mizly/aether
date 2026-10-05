package dev.aether.ui.settings;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import dev.aether.ui.gui.Icon;
import dev.aether.util.AetherLang;

// just holds the SubTab type now; MainGUI does the rendering
public class ModulesTab {

    // feature switches gate a feature; runtime switches act right away (open the PiP window, free the cursor)
    public enum ToggleKind { FEATURE, RUNTIME }

    // named grouping of SettingGroups with its own header metadata and state. names are localised once, at
    // construction; copies made by the with* methods keep them, so nothing is ever localised twice
    public static final class SubTab {
        private final String rawName;
        private final String rawDescription;
        private final String name;
        private final String description;
        private final String id;
        private final BooleanSupplier enabledGetter;
        private final Consumer<Boolean> enabledSetter;
        private final List<SettingGroup> groups;
        private final Icon icon;
        private final ToggleKind toggleKind;

        public SubTab(String name, String description, BooleanSupplier enabledGetter, Consumer<Boolean> enabledSetter,
                      List<SettingGroup> groups) {
            this(name, description, AetherLang.localize(name), AetherLang.localize(description), enabledGetter,
                    enabledSetter, groups, null, ToggleKind.FEATURE);
        }

        public SubTab(String name, String description, List<SettingGroup> groups) {
            this(name, description, null, null, groups);
        }

        private SubTab(String rawName, String rawDescription, String name, String description,
                       BooleanSupplier enabledGetter, Consumer<Boolean> enabledSetter, List<SettingGroup> groups,
                       Icon icon, ToggleKind toggleKind) {
            this.rawName = rawName;
            this.rawDescription = rawDescription;
            this.name = name;
            this.description = description;
            this.id = idFor(rawName);
            this.enabledGetter = enabledGetter;
            this.enabledSetter = enabledSetter;
            this.groups = groups;
            this.icon = icon;
            this.toggleKind = toggleKind;
        }

        // "Auto Carnival (Shootout)" becomes "auto-carnival-shootout"; stable across languages
        public static String idFor(String rawName) {
            if (rawName == null) {
                return "";
            }
            String slug = rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            return slug.replaceAll("^-+|-+$", "");
        }

        public SubTab withIcon(Icon icon) {
            return new SubTab(rawName, rawDescription, name, description, enabledGetter, enabledSetter, groups,
                    icon, toggleKind);
        }

        public SubTab withToggleKind(ToggleKind toggleKind) {
            return new SubTab(rawName, rawDescription, name, description, enabledGetter, enabledSetter, groups,
                    icon, toggleKind);
        }

        public String name() {
            return name;
        }

        public String description() {
            return description;
        }

        public String rawName() {
            return rawName;
        }

        public String rawDescription() {
            return rawDescription;
        }

        public String id() {
            return id;
        }

        public Icon icon() {
            return icon;
        }

        public ToggleKind toggleKind() {
            return toggleKind;
        }

        public BooleanSupplier enabledGetter() {
            return enabledGetter;
        }

        public Consumer<Boolean> enabledSetter() {
            return enabledSetter;
        }

        // the live list: providers rebuild it in place, so read it every frame instead of copying it
        public List<SettingGroup> groups() {
            return groups;
        }

        public boolean hasToggle() {
            return enabledGetter != null && enabledSetter != null;
        }

        public boolean isEnabled() {
            return !hasToggle() || enabledGetter.getAsBoolean();
        }

        public void toggle() {
            if (hasToggle()) enabledSetter.accept(!isEnabled());
        }
    }

    private ModulesTab() {}
}
