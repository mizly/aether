package dev.aether.ui.settings;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import dev.aether.util.AetherLang;

// just holds the SubTab record now; MainGUI does the rendering
public class ModulesTab {

    // named grouping of SettingGroups with its own header metadata and state
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
