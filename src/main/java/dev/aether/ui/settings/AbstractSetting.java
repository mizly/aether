package dev.aether.ui.settings;

import dev.aether.config.entries.ConfigEntry;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// what every setting shares: its name (localised once, here), visibility, an explicit description, the
// config entries it writes and, for second copies of a knob, the page that owns it
public abstract class AbstractSetting<S extends AbstractSetting<S>> implements Setting {
    private final String rawName;
    private final String name;
    private Supplier<Boolean> visibility = () -> true;
    private String description;
    private List<ConfigEntry<?>> bindings = List.of();
    private String mirrorOf;

    protected AbstractSetting(String rawName) {
        this.rawName = rawName;
        this.name = AetherLang.localize(rawName);
    }

    public final S visibleWhen(Supplier<Boolean> condition) {
        visibility = condition;
        return self();
    }

    // raw english like every other label; shown inline, unlike the catalog's generated fallback
    public final S describe(String description) {
        this.description = description;
        return self();
    }

    // the entries this setting writes, which gives it reset to default and the modified mark
    public final S bind(ConfigEntry<?>... entries) {
        List<ConfigEntry<?>> all = new ArrayList<>(bindings);
        all.addAll(List.of(entries));
        bindings = List.copyOf(all);
        return self();
    }

    // a copy of a knob whose canonical home is another page, e.g. a pest aim value repeated on Humanization
    public final S mirrorOf(String pageId) {
        mirrorOf = pageId;
        return self();
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
    public boolean isVisible() {
        return visibility.get();
    }

    @Override
    public String explicitDescription() {
        return description != null ? AetherLang.localize(description) : Setting.super.explicitDescription();
    }

    @Override
    public List<ConfigEntry<?>> bindings() {
        return bindings;
    }

    @Override
    public String mirrorOf() {
        return mirrorOf;
    }

    @SuppressWarnings("unchecked")
    private S self() {
        return (S) this;
    }
}
