package dev.aether.ui.gui;

// the focused editor of one layer, the page or a single overlay; focusing another commits the old one first
public final class EditorSlot {
    private FocusedEditor focused;

    public void focus(FocusedEditor editor) {
        FocusedEditor previous = focused;
        focused = editor;
        if (previous != null && previous != editor) {
            previous.commit();
        }
    }

    public FocusedEditor focused() {
        return focused;
    }

    public boolean isFocused(Object id) {
        return focused != null && focused.id().equals(id);
    }

    // clears first, so a commit that moves focus cannot recurse back into this slot
    public void commit() {
        FocusedEditor editor = focused;
        focused = null;
        if (editor != null) {
            editor.commit();
        }
    }

    public void escape() {
        FocusedEditor editor = focused;
        focused = null;
        if (editor != null) {
            editor.escape();
        }
    }

    // the editor already committed itself
    public void release() {
        focused = null;
    }
}
