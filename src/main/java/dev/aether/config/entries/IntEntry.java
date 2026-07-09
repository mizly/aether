package dev.aether.config.entries;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class IntEntry extends ConfigEntry<Integer> {
    private int min = Integer.MIN_VALUE;
    private int max = Integer.MAX_VALUE;

    public IntEntry(String key, int defaultValue) {
        super(key, defaultValue);
    }

    public IntEntry range(int min, int max) {
        this.min = min;
        this.max = max;
        return this;
    }

    @Override
    public void set(Integer value) {
        this.value = Math.max(min, Math.min(max, value));
        markChanged();
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && !element.isJsonNull())
            set(element.getAsInt());
    }
}
