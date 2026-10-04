package dev.aether.ui.gui;

// a clock that only moves when told to, for unit tests and the preview harness
public final class ManualClock implements GuiClock {
    private long nanos;

    public ManualClock() {
        this(1_000_000_000L);
    }

    public ManualClock(long startNanos) {
        this.nanos = startNanos;
    }

    @Override
    public long nanos() {
        return nanos;
    }

    public void advanceMillis(long millis) {
        nanos += millis * 1_000_000L;
    }
}
