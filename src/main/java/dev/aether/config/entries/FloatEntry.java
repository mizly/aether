package dev.aether.config.entries;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class FloatEntry extends ConfigEntry<Float> {
    private float min = -Float.MAX_VALUE;
    private float max = Float.MAX_VALUE;

    public FloatEntry(String key, float defaultValue) {
        super(key, defaultValue);
    }

    public FloatEntry range(float min, float max) {
        this.min = min;
        this.max = max;
        return this;
    }

    @Override
    public void set(Float value) {
        this.value = Math.max(min, Math.min(max, value));
        markChanged();
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && !element.isJsonNull())
            set(element.getAsFloat());
    }
}
