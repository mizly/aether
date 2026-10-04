package dev.aether.ui.gui.control;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

// small per-view state a control keeps between frames by id (a capture start time, a pending choice)
public final class ControlMemory {
    private final Map<Object, Object> values = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Supplier<T> initial) {
        return (T) values.computeIfAbsent(key, k -> initial.get());
    }

    @SuppressWarnings("unchecked")
    public <T> T peek(Object key) {
        return (T) values.get(key);
    }

    public void put(Object key, Object value) {
        values.put(key, value);
    }

    public void remove(Object key) {
        values.remove(key);
    }

    public void clear() {
        values.clear();
    }
}
