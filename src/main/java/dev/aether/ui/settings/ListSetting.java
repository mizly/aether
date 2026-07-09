package dev.aether.ui.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.util.AetherLang;

public class ListSetting implements Setting {

    private final String name;
    private final String rawName;
    private final String placeholder;
    private final Supplier<List<String>> getter;
    private final Consumer<List<String>> setter;
    private Supplier<Boolean> visibility = () -> true;

    public ListSetting(String name, String placeholder,
                       Supplier<List<String>> getter, Consumer<List<String>> setter) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.placeholder = AetherLang.localize(placeholder);
        this.getter = getter;
        this.setter = setter;
    }

    public List<String> getValues() {
        return new ArrayList<>(getter.get());
    }

    public void setValues(List<String> values) {
        setter.accept(new ArrayList<>(values));
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public ListSetting visibleWhen(Supplier<Boolean> condition) {
        this.visibility = condition;
        return this;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getRawName() {
        return rawName;
    }

    @Override
    public SettingType getType() {
        return SettingType.LIST;
    }

    @Override
    public boolean isVisible() {
        return visibility.get();
    }
}
