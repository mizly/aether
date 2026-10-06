package dev.aether.ui.gui;

// what a keybind capture writes to; keybind controls bind through GuiHost so options are saved there
public interface KeyTarget {
    void bind(BoundKey key);

    void clear();
}
