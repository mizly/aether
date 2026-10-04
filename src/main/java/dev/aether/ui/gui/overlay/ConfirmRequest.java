package dev.aether.ui.gui.overlay;

// a yes/no dialog for destructive actions; labels are already localised, onCancel may be null
public record ConfirmRequest(String title, String message, String confirmLabel, String cancelLabel,
                             boolean destructive, Runnable onConfirm, Runnable onCancel) {
    public static ConfirmRequest destructive(String title, String message, String confirmLabel, String cancelLabel,
                                             Runnable onConfirm) {
        return new ConfirmRequest(title, message, confirmLabel, cancelLabel, true, onConfirm, null);
    }
}
