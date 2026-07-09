package dev.aether.ui.settings;

import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.aether.util.AetherLang;

/**
 * Text input setting backed by a getter/setter.
 *
 * Example:
 *   new TextSetting("Discord Webhook", "https://...",
 *       () -> AetherConfig.discordWebhookUrl,
 *       v -> AetherConfig.discordWebhookUrl = v)
 */
public class TextSetting implements Setting {

    private final String name;
    private final String rawName;
    private final String placeholder;
    private final Supplier<String> getter;
    private final Consumer<String> setter;
    private boolean multiline = false;
    private int visibleLines = 4;
    private Supplier<Boolean> visibility = () -> true;

    public TextSetting(String name, String placeholder,
                       Supplier<String> getter, Consumer<String> setter) {
        this.rawName = name;
        this.name = AetherLang.localize(name);
        this.placeholder = AetherLang.localize(placeholder);
        this.getter = getter;
        this.setter = setter;
    }

    public String getValue() { return getter.get(); }
    public void setValue(String value) { setter.accept(value); }
    public String getPlaceholder() { return placeholder; }
    public boolean isMultiline() { return multiline; }
    public int getVisibleLines() { return visibleLines; }

    public TextSetting visibleWhen(Supplier<Boolean> condition) {
        this.visibility = condition;
        return this;
    }

    public TextSetting multiline() {
        return multiline(4);
    }

    public TextSetting multiline(int visibleLines) {
        this.multiline = true;
        this.visibleLines = Math.max(2, visibleLines);
        return this;
    }

    @Override public String getName() { return name; }
    @Override public String getRawName() { return rawName; }
    @Override public SettingType getType() { return SettingType.TEXT; }
    @Override public boolean isVisible() { return visibility.get(); }
}
