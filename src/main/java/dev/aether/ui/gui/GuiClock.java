package dev.aether.ui.gui;

// the gui's only time source, so the preview harness can step frames deterministically
@FunctionalInterface
public interface GuiClock {
    GuiClock SYSTEM = System::nanoTime;

    long nanos();
}
