package dev.aether.config.entries;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class StringEntry extends ConfigEntry<String> {
    public StringEntry(String key, String defaultValue) {
        super(key, defaultValue);
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value != null ? value : ""); }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && !element.isJsonNull())
            set(element.getAsString());
    }
}
