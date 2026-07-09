package dev.aether.config.entries;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class BooleanEntry extends ConfigEntry<Boolean> {
    public BooleanEntry(String key, boolean defaultValue) {
        super(key, defaultValue);
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && !element.isJsonNull())
            set(element.getAsBoolean());
    }
}
