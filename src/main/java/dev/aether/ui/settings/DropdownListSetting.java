package dev.aether.ui.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.util.AetherLang;

public class DropdownListSetting implements Setting {

    private final String name;
    private final String rawName;
    private final List<String> allOptions;
    private final DropdownSetting addPicker;
    private final Supplier<List<String>> getter;
    private final Consumer<List<String>> setter;
    private Supplier<Boolean> visibility = () -> true;

    public DropdownListSetting(String name, List<String> allOptions,
                               Supplier<List<String>> getter, Consumer<List<String>> setter) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.allOptions = List.copyOf(allOptions);
        int[] pendingIdx = {0};
        this.addPicker = new DropdownSetting("", allOptions, () -> pendingIdx[0], i -> pendingIdx[0] = i);
        this.getter = getter;
        this.setter = setter;
    }

    public List<String> getValues() {
        return new ArrayList<>(getter.get());
    }

    public void addValue(String v) {
        List<String> vals = getValues();
        if (!vals.contains(v)) {
            vals.add(v);
            setter.accept(vals);
        }
    }

    public void removeValue(int index) {
        List<String> vals = getValues();
        if (index >= 0 && index < vals.size()) {
            vals.remove(index);
            setter.accept(vals);
        }
    }

    public void moveUp(int index) {
        List<String> vals = getValues();
        if (index > 0 && index < vals.size()) {
            String tmp = vals.get(index - 1);
            vals.set(index - 1, vals.get(index));
            vals.set(index, tmp);
            setter.accept(vals);
        }
    }

    public void moveDown(int index) {
        List<String> vals = getValues();
        if (index >= 0 && index < vals.size() - 1) {
            String tmp = vals.get(index + 1);
            vals.set(index + 1, vals.get(index));
            vals.set(index, tmp);
            setter.accept(vals);
        }
    }

    public List<String> getAllOptions() {
        return allOptions;
    }

    public DropdownSetting getAddPicker() {
        return addPicker;
    }

    public DropdownListSetting visibleWhen(Supplier<Boolean> condition) {
        this.visibility = condition;
        return this;
    }

    @Override public String getName()      { return name; }
    @Override public String getRawName()   { return rawName; }
    @Override public SettingType getType() { return SettingType.DROPDOWN_LIST; }
    @Override public boolean isVisible()   { return visibility.get(); }
}
