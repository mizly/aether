package dev.aether.ui.settings;

import java.util.function.Consumer;
import java.util.function.Supplier;

// on/off backed by a getter/setter
public class ToggleSetting extends AbstractSetting<ToggleSetting> {

    private final Supplier<Boolean> getter;
    private final Consumer<Boolean> setter;

    public ToggleSetting(String name, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        super(name);
        this.getter = getter;
        this.setter = setter;
    }

    public boolean getValue() { return getter.get(); }
    public void setValue(boolean value) { setter.accept(value); }
    public void toggle() { setValue(!getValue()); }

    @Override public SettingType getType() { return SettingType.TOGGLE; }
}
