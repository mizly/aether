package dev.aether.renderer;

import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.network.chat.Style;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftGlyphQuadTest {
    @Test
    void preservesVanillaBoldItalicAndShadowGeometry() {
        var glyph = new BakedSheetGlyph(null, null, null, 0.25f, 0.5f, 0.5f, 0.75f, 0, 5, 0, 8);
        List<CapturedQuad> captured = new ArrayList<>();
        var consumer = new MinecraftGlyphQuad(q -> captured.add(new CapturedQuad(q.x.clone(), q.y.clone(), q.u.clone(), q.v.clone(), q.color)));
        glyph.createGlyph(10, 20, 0xFF12AB34, 0x80402010, Style.EMPTY.withBold(true).withItalic(true), 1, 1)
                .render(new Matrix4f(), consumer, 0xF000F0, true);
        consumer.flush();

        assertEquals(4, captured.size());
        assertEquals(0x80402010, captured.get(0).color());
        assertEquals(0x80402010, captured.get(1).color());
        assertEquals(0xFF12AB34, captured.get(2).color());
        assertEquals(0xFF12AB34, captured.get(3).color());
        assertArrayEquals(new float[]{10.9f, 8.9f, 14.1f, 16.1f}, captured.get(2).x(), 0.0001f);
        assertArrayEquals(new float[]{19.9f, 28.1f, 28.1f, 19.9f}, captured.get(2).y(), 0.0001f);
        assertArrayEquals(new float[]{0.25f, 0.25f, 0.5f, 0.5f}, captured.get(2).u());
        assertArrayEquals(new float[]{0.5f, 0.75f, 0.75f, 0.5f}, captured.get(2).v());
        for (int vertex = 0; vertex < 4; vertex++) {
            assertEquals(captured.get(2).x()[vertex] + 1, captured.get(3).x()[vertex], 0.0001f);
        }
    }

    @Test
    void retainsFinalVertexAttributesAndCanBeReused() {
        List<Integer> colors = new ArrayList<>();
        var consumer = new MinecraftGlyphQuad(q -> colors.add(q.color));
        for (int pass = 0; pass < 2; pass++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                consumer.addVertex(vertex, vertex, 0).setUv(vertex, vertex).setColor(1, 2, 3, 128);
            }
            consumer.flush();
        }
        assertEquals(List.of(0x80010203, 0x80010203), colors);
    }

    private record CapturedQuad(float[] x, float[] y, float[] u, float[] v, int color) { }
}
