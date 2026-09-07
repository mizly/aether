package dev.aether.renderer;

import com.mojang.blaze3d.vertex.VertexConsumer;

final class MinecraftGlyphQuad implements VertexConsumer {
    interface Sink {
        void draw(MinecraftGlyphQuad quad);
    }

    private final Sink sink;
    final float[] x = new float[4];
    final float[] y = new float[4];
    final float[] u = new float[4];
    final float[] v = new float[4];
    int color;
    private int vertices;

    MinecraftGlyphQuad(Sink sink) {
        this.sink = sink;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (vertices == 4) flush();
        this.x[vertices] = x;
        this.y[vertices] = y;
        vertices++;
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        this.color = color;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        return setColor(a << 24 | r << 16 | g << 8 | b);
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.u[vertices - 1] = u;
        this.v[vertices - 1] = v;
        return this;
    }

    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }

    void flush() {
        if (vertices == 0) return;
        if (vertices != 4) throw new IllegalStateException("Incomplete Minecraft glyph quad");
        sink.draw(this);
        vertices = 0;
    }
}
