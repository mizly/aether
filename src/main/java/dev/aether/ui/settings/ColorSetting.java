package dev.aether.ui.settings;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class ColorSetting extends AbstractSetting<ColorSetting> {

    private final Supplier<Integer> getter;
    private final Consumer<Integer> setter;

    public ColorSetting(String name, Supplier<Integer> getter, Consumer<Integer> setter) {
        super(name);
        this.getter = getter;
        this.setter = setter;
    }

    public int getValue()       { return getter.get(); }
    public void setValue(int v) { setter.accept(v); }

    @Override public SettingType getType() { return SettingType.COLOR; }
}
