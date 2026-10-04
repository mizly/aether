package dev.aether.ui.gui;

// a field that owns keyboard input while focused; it lives in one layer's EditorSlot
public interface FocusedEditor {
    // done means the editor already committed itself (enter on a single line) and gives up focus
    enum Result { IGNORED, HANDLED, DONE }

    // the field's hit region id, so pressing the field again keeps the editor instead of committing it
    Object id();

    // escape never arrives here, the view routes it to escape()
    Result key(KeyInput key);

    boolean chars(String chars);

    void commit();

    // setting fields commit; the profile name fields override this to cancel and the search field to clear
    default void escape() {
        commit();
    }
}
