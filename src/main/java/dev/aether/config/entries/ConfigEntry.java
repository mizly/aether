package dev.aether.config.entries;

import com.google.gson.JsonElement;
import dev.aether.config.Config;

public abstract class ConfigEntry<T> {
    protected final String key;
    protected final T defaultValue;
    protected T value;
    private boolean persistent = true;

    protected ConfigEntry(String key, T defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String getKey()      { return key; }
    public T get()              { return value; }
    public void set(T value)    { this.value = value; markChanged(); }
    public void reset()         { this.value = defaultValue; markChanged(); }
    public T getDefault()       { return defaultValue; }
    public boolean isPersistent() { return persistent; }

    /** Mark this entry as volatile - not written to the config file. */
    @SuppressWarnings("unchecked")
    public <E extends ConfigEntry<T>> E nonPersistent() {
        this.persistent = false;
        return (E) this;
    }

    protected final void markChanged() {
        Config.onEntryChanged();
    }

    public abstract JsonElement toJson();
    public abstract void fromJson(JsonElement element);
}
