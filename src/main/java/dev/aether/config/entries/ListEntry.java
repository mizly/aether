package dev.aether.config.entries;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.List;

public final class ListEntry<T> extends ConfigEntry<List<T>> {
    private final Class<T> elementType;

    public ListEntry(String key, List<T> defaultValue, Class<T> elementType) {
        super(key, new ArrayList<>(defaultValue));
        this.value = new ArrayList<>(defaultValue);
        this.elementType = elementType;
    }

    public void addItem(T item)    { value.add(item); markChanged(); }
    public void removeItem(T item) { value.remove(item); markChanged(); }
    public boolean contains(T item){ return value.contains(item); }

    @Override
    public void set(List<T> value) {
        this.value = new ArrayList<>(value);
        markChanged();
    }

    @Override
    public void reset() {
        this.value = new ArrayList<>(defaultValue);
        markChanged();
    }

    @Override
    public JsonElement toJson() {
        JsonArray arr = new JsonArray();
        for (T item : value) arr.add(item.toString());
        return arr;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonArray()) return;
        List<T> list = new ArrayList<>();
        for (JsonElement el : element.getAsJsonArray()) {
            if (elementType == String.class)
                list.add((T) el.getAsString());
            else if (elementType == Integer.class)
                list.add((T) Integer.valueOf(el.getAsInt()));
        }
        set(list);
    }
}
