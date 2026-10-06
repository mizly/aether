package dev.aether.ui.gui;

// a hit handler that also implements this answers copy and paste shortcuts while it is hovered and no
// editor has focus, like the colour swatches do
public interface ClipboardTarget {
    String copyText();

    // false when the text is not something this target accepts
    boolean paste(String text);
}
