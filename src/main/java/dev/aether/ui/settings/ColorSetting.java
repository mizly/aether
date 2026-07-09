package dev.aether.ui.settings;

import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.util.AetherLang;

public class ColorSetting implements Setting {

    private final String name;
    private final String rawName;
    private final Supplier<Integer> getter;
    private final Consumer<Integer> setter;
    private Supplier<Boolean> visibilityCondition = null;

    public ColorSetting(String name, Supplier<Integer> getter, Consumer<Integer> setter) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.getter = getter;
        this.setter = setter;
    }

    public ColorSetting visibleWhen(Supplier<Boolean> condition) {
        this.visibilityCondition = condition;
        return this;
    }

    public int getValue()       { return getter.get(); }
    public void setValue(int v) { setter.accept(v); }

    @Override public String getName()    { return name; }
    @Override public String getRawName() { return rawName; }
    @Override public SettingType getType() { return SettingType.COLOR; }
    @Override public boolean isVisible() { return visibilityCondition == null || visibilityCondition.get(); }
}
