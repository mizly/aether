package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Clipboard;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.TextEditor;
import org.lwjgl.glfw.GLFW;

// the one text editor with keyboard focus. a field focuses itself with a Session that knows how to write
// the text back; the editor commits when focus moves, the pointer presses outside it, its overlay closes or
// the field stops being drawn, so typed text is never lost
public final class EditorFocus {
    public enum Escape { COMMIT, CANCEL, CLEAR }

    public interface Session {
        TextEditor editor();

        void commit(String text);

        default void cancel() {
        }

        default Escape escape() {
            return Escape.COMMIT;
        }

        // every edit, for fields that apply live (search)
        default void edited(String text) {
        }
    }

    private Object id;
    private Session session;
    private Object owner;
    private Rect bounds = Rect.EMPTY;
    private boolean drawn;
    private int lastRevision;

    // owner is the layer the field lives in (an overlay, or null for the page)
    public void focus(Object id, Session session, Object owner) {
        if (this.id != null && this.id.equals(id)) {
            return;
        }
        commit();
        this.id = id;
        this.session = session;
        this.owner = owner;
        this.bounds = Rect.EMPTY;
        this.drawn = true;
        this.lastRevision = session.editor().revision();
    }

    public boolean active() {
        return session != null;
    }

    public boolean focused(Object id) {
        return this.id != null && this.id.equals(id);
    }

    public Object focusedId() {
        return id;
    }

    public Object owner() {
        return owner;
    }

    public TextEditor editor() {
        return session == null ? null : session.editor();
    }

    // the focused field reports its root-space rect every frame it draws
    public void drawn(Object id, Rect rootBounds) {
        if (focused(id)) {
            bounds = rootBounds;
            drawn = true;
        }
    }

    public Rect bounds() {
        return bounds;
    }

    // a field that was not drawn for a whole frame is gone (page change, culled row): keep its text
    public void endFrame() {
        if (session != null && !drawn) {
            commit();
        }
        drawn = false;
    }

    public void commit() {
        Session current = session;
        if (current == null) {
            return;
        }
        clear();
        current.commit(current.editor().text());
    }

    public void cancel() {
        Session current = session;
        if (current == null) {
            return;
        }
        clear();
        current.cancel();
    }

    public void commitOwnedBy(Object layer) {
        if (session != null && owner == layer) {
            commit();
        }
    }

    // a press outside the focused field commits it; the press then carries on to whatever it hit
    public void pointerPressed(float rootX, float rootY) {
        if (session != null && !bounds.contains(rootX, rootY)) {
            commit();
        }
    }

    public boolean key(KeyInput k, Clipboard clipboard) {
        if (session == null) {
            return false;
        }
        if (k.is(GLFW.GLFW_KEY_ESCAPE)) {
            switch (session.escape()) {
                case COMMIT -> commit();
                case CANCEL -> cancel();
                case CLEAR -> {
                    TextEditor editor = session.editor();
                    if (editor.text().isEmpty()) {
                        cancel();
                    } else {
                        editor.setText("");
                        notifyEdit();
                    }
                }
            }
            return true;
        }
        TextEditor.Result result = session.editor().key(k, clipboard);
        switch (result) {
            case SUBMIT -> {
                commit();
                return true;
            }
            case HANDLED -> {
                notifyEdit();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public boolean chars(String chars) {
        if (session == null) {
            return false;
        }
        session.editor().chars(chars);
        notifyEdit();
        return true;
    }

    private void notifyEdit() {
        if (session == null) {
            return;
        }
        int revision = session.editor().revision();
        if (revision != lastRevision) {
            lastRevision = revision;
            session.edited(session.editor().text());
        }
    }

    private void clear() {
        id = null;
        session = null;
        owner = null;
        bounds = Rect.EMPTY;
    }
}
