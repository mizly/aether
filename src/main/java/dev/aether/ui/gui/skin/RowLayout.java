package dev.aether.ui.gui.skin;

// how a setting row arranges its label and control
public enum RowLayout {
    LABEL_LEFT_CONTROL_RIGHT,
    // the label is drawn inside the control, like vanilla's "Label: ON" buttons
    LABEL_IN_CONTROL,
    // label on top, control across the full width below
    STACKED
}
