package dev.aether.ui.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.util.AetherLang;

public class ListSetting extends AbstractSetting<ListSetting> {

    private final String placeholder;
    private final Supplier<List<String>> getter;
    private final Consumer<List<String>> setter;

    public ListSetting(String name, String placeholder,
                       Supplier<List<String>> getter, Consumer<List<String>> setter) {
        super(name);
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

    @Override
    public SettingType getType() {
        return SettingType.LIST;
    }
}
