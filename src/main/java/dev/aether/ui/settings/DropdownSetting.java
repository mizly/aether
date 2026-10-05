package dev.aether.ui.settings;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.ui.gui.Icon;
import dev.aether.util.AetherLang;

// dropdown backed by an index getter/setter
public class DropdownSetting extends AbstractSetting<DropdownSetting> {

    private final List<String> options;
    private final List<IconAction> iconActions = new ArrayList<>();
    private final Supplier<Integer> indexGetter;
    private final Consumer<Integer> indexSetter;
    private List<Icon> optionIcons = List.of();
    private boolean confirmChange;

    public DropdownSetting(String name, List<String> options,
                           Supplier<Integer> indexGetter, Consumer<Integer> indexSetter) {
        super(name);
        this.options = options;
        this.indexGetter = indexGetter;
        this.indexSetter = indexSetter;
    }

    public List<String> getOptions() { return options.stream().map(AetherLang::localize).toList(); }
    public List<IconAction> getIconActions() { return iconActions; }
    public int getSelectedIndex() { return indexGetter.get(); }
    public void setSelectedIndex(int index) { indexSetter.accept(index); }
    public String getSelectedOption() {
        if (options.isEmpty()) {
            return "";
        }

        int index = getSelectedIndex();
        if (index < 0 || index >= options.size()) {
            return AetherLang.localize(options.getFirst());
        }

        return AetherLang.localize(options.get(index));
    }

    public DropdownSetting addIconAction(String iconPath, Runnable action) {
        iconActions.add(new IconAction(iconPath, action));
        return this;
    }

    // by option index, because option text is localised for display; null entries mean no icon
    public DropdownSetting optionIcons(List<Icon> icons) {
        optionIcons = new ArrayList<>(icons);
        return this;
    }

    public Icon optionIcon(int index) {
        return index >= 0 && index < optionIcons.size() ? optionIcons.get(index) : null;
    }

    // the setter is destructive (it rebuilds groups or overwrites other values), so a picker must never
    // commit options while cycling through them; it opens the menu instead
    public DropdownSetting confirmChange() {
        confirmChange = true;
        return this;
    }

    public boolean confirmsChange() {
        return confirmChange;
    }

    @Override public SettingType getType() { return SettingType.DROPDOWN; }

    public record IconAction(String iconPath, Runnable action) {
        public void execute() {
            if (action != null) action.run();
        }
    }
}
