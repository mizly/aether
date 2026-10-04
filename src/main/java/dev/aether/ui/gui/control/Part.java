package dev.aether.ui.gui.control;

// a hit, focus or animation key for one part of a control, built from the control's stable id
public record Part(Object owner, String name, int index) {
    public static Part of(Object owner, String name) {
        return new Part(owner, name, 0);
    }

    public static Part of(Object owner, String name, int index) {
        return new Part(owner, name, index);
    }
}
