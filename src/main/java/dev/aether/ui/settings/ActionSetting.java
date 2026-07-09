package dev.aether.ui.settings;

import java.util.function.Supplier;
import dev.aether.util.AetherLang;

/**
 * Button/action setting that executes a runnable when clicked.
 *
 * Example:
 *   new ActionSetting("Save Config", AetherConfig::save)
 */
public class ActionSetting implements Setting {

    private final String name;
    private final String rawName;
    private final Runnable action;
    private Supplier<Boolean> visibility = () -> true;

    public ActionSetting(String name, Runnable action) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.action = action;
    }

    public void execute() {
        if (action != null) action.run();
    }

    public ActionSetting visibleWhen(Supplier<Boolean> condition) {
        this.visibility = condition;
        return this;
    }

    @Override public String getName() { return name; }
    @Override public String getRawName() { return rawName; }
    @Override public SettingType getType() { return SettingType.ACTION; }
    @Override public boolean isVisible() { return visibility.get(); }
}
