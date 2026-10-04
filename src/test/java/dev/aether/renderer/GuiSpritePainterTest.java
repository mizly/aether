package dev.aether.renderer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuiSpritePainterTest {

    @Test
    void readsVanillaGuiScaling() {
        assertEquals(new McTextures.Scaling.NineSlice(200, 20, 3, 3, 3, 3, false),
                McTextures.scaling("minecraft:widget/button"));
        assertEquals(new McTextures.Scaling.NineSlice(8, 20, 2, 2, 2, 3, false),
                McTextures.scaling("minecraft:widget/slider_handle"));
        assertEquals(new McTextures.Scaling.NineSlice(100, 100, 10, 10, 10, 10, true),
                McTextures.scaling("minecraft:textures/gui/sprites/tooltip/frame.png"));
        assertInstanceOf(McTextures.Scaling.Stretch.class,
                McTextures.scaling("minecraft:textures/gui/container/generic_54.png"));
        assertInstanceOf(McTextures.Scaling.Stretch.class, McTextures.scaling("minecraft:widget/checkbox"));
        assertEquals("minecraft:textures/gui/sprites/widget/button.png", McTextures.spriteTexture("widget/button"));
    }

    @Test
    void aButtonNarrowerThanItsSpriteKeepsBothEndsAndCropsTheMiddle() {
        List<GuiSpritePainter.Piece> pieces = GuiSpritePainter.pieces(McTextures.scaling("minecraft:widget/button"),
                200, 20, 10, 5, 100, 20);
        assertEquals(List.of(
                new GuiSpritePainter.Piece(0, 0, 3, 20, 10, 5, 3, 20),
                new GuiSpritePainter.Piece(3, 0, 94, 20, 13, 5, 94, 20),
                new GuiSpritePainter.Piece(197, 0, 3, 20, 107, 5, 3, 20)), pieces);
    }

    @Test
    void tilesTheMiddleOfAWideButtonAndScalesSourcesForHdPacks() {
        List<GuiSpritePainter.Piece> pieces = GuiSpritePainter.pieces(McTextures.scaling("minecraft:widget/button"),
                400, 40, 0, 0, 300, 20);
        assertEquals(4, pieces.size());
        assertEquals(new GuiSpritePainter.Piece(6, 0, 388, 40, 3, 0, 194, 20), pieces.get(1));
        assertEquals(new GuiSpritePainter.Piece(6, 0, 200, 40, 197, 0, 100, 20), pieces.get(2));
    }

    @Test
    void stretchesTheInsideOfStretchInnerSlicesAndClampsBordersToHalfTheSize() {
        McTextures.Scaling frame = new McTextures.Scaling.NineSlice(100, 100, 10, 10, 10, 10, true);
        List<GuiSpritePainter.Piece> pieces = GuiSpritePainter.pieces(frame, 100, 100, 0, 0, 180, 60);
        assertEquals(9, pieces.size());
        assertTrue(pieces.contains(new GuiSpritePainter.Piece(10, 10, 80, 80, 10, 10, 160, 40)));
        List<GuiSpritePainter.Piece> tiny = GuiSpritePainter.pieces(frame, 100, 100, 0, 0, 12, 7);
        assertTrue(tiny.contains(new GuiSpritePainter.Piece(0, 0, 6, 3, 0, 0, 6, 3)), tiny.toString());
    }

    @Test
    void tilesWholeSprites() {
        List<GuiSpritePainter.Piece> pieces = GuiSpritePainter.pieces(new McTextures.Scaling.Tile(16, 16), 32, 32,
                0, 0, 40, 16);
        assertEquals(List.of(
                new GuiSpritePainter.Piece(0, 0, 32, 32, 0, 0, 16, 16),
                new GuiSpritePainter.Piece(0, 0, 32, 32, 16, 0, 16, 16),
                new GuiSpritePainter.Piece(0, 0, 16, 32, 32, 0, 8, 16)), pieces);
    }
}
