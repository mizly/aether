package dev.aether.ui.gui;

// every char advances size * ADVANCE and lines are size * LINE tall, so tests can predict layout exactly
public final class MonospaceTextMetrics implements TextMetrics {
    public static final float ADVANCE = 0.5f;
    public static final float LINE = 1.25f;

    private int widthCalls;

    @Override
    public float width(String font, float size, String text) {
        widthCalls++;
        return text.length() * size * ADVANCE;
    }

    @Override
    public float lineHeight(String font, float size) {
        return size * LINE;
    }

    @Override
    public float[] caretX(String font, float size, String text) {
        float[] carets = new float[text.length() + 1];
        for (int i = 0; i < carets.length; i++) {
            carets[i] = i * size * ADVANCE;
        }
        return carets;
    }

    public int widthCalls() {
        return widthCalls;
    }
}
