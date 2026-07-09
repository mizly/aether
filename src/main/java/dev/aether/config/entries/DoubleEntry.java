package dev.aether.config.entries;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class DoubleEntry extends ConfigEntry<Double> {
    public DoubleEntry(String key, double defaultValue) {
        super(key, defaultValue);
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && !element.isJsonNull())
            set(element.getAsDouble());
    }
}
