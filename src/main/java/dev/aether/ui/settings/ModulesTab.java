package dev.aether.ui.settings;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import dev.aether.util.AetherLang;

/**
 * Container for the SubTab record used by ClickGuiRegistry and NvgMenuScreen.
 * The old rendering logic has been replaced by NvgMenuScreen.
 */
public class ModulesTab {

    /** Named grouping of SettingGroups with independent header metadata/state. */
    public record SubTab(
            String name,
            String description,
            BooleanSupplier enabledGetter,
            Consumer<Boolean> enabledSetter,
            List<SettingGroup> groups) {

        public SubTab {
            name = AetherLang.localize(name);
            description = AetherLang.localize(description);
        }

        public SubTab(String name, String description, List<SettingGroup> groups) {
            this(name, description, null, null, groups);
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
