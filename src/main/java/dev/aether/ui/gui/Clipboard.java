package dev.aether.ui.gui;

// the system clipboard as the host exposes it; the preview host keeps it in memory
public interface Clipboard {
    String read();

    void write(String text);
}
