package dev.aether.ui.gui;

// keyboard behaviour of one focusable element, implemented once per control
public interface FocusHandler {
    // enter or space
    void activate();

    // left or right; large is shift held, a ten times bigger step for sliders
    default void adjust(int direction, boolean large) {
    }
}
