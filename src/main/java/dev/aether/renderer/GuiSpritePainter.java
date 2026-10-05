package dev.aether.renderer;

import java.util.ArrayList;
import java.util.List;

// vanilla's gui sprite blits: nine-slice corners 1:1 with edges and centre tiled (or stretched with stretch_inner),
// whole-sprite tiling, or a plain stretch; one sprite pixel per local unit
final class GuiSpritePainter {

    // tiling a tiny tile over a huge area would flood nanovg with fills; past this it stretches instead
    private static final int MAX_TILES = 1024;

    // src is in texture pixels, dst in local units
    record Piece(float srcX, float srcY, float srcW, float srcH, float x, float y, float w, float h) {}

    private GuiSpritePainter() {}

    static void draw(NVGRenderer nvg, McTextures.GuiSprite sprite, float x, float y, float w, float h, int tint) {
        McTextures.Texture texture = sprite.texture();
        if (texture.missing() || w <= 0f || h <= 0f) return;
        for (Piece piece : pieces(sprite.scaling(), texture.width(), texture.height(), x, y, w, h)) {
            nvg.imageRegion(texture.handle(), texture.width(), texture.height(), piece.srcX(), piece.srcY(),
                    piece.srcW(), piece.srcH(), piece.x(), piece.y(), piece.w(), piece.h(), tint);
        }
    }

    static List<Piece> pieces(McTextures.Scaling scaling, int textureW, int textureH, float x, float y, float w, float h) {
        List<Piece> pieces = new ArrayList<>();
        if (w <= 0f || h <= 0f) return pieces;
        switch (scaling) {
            case McTextures.Scaling.Tile tile -> {
                Slicer slicer = new Slicer(pieces, textureW / (float) tile.width(), textureH / (float) tile.height());
                slicer.tiled(x, y, w, h, 0, 0, tile.width(), tile.height());
            }
            case McTextures.Scaling.NineSlice nine -> nineSlice(pieces, nine, textureW, textureH, x, y, w, h);
            case McTextures.Scaling.Stretch stretch -> pieces.add(new Piece(0, 0, textureW, textureH, x, y, w, h));
        }
        return pieces;
    }

    private static void nineSlice(List<Piece> pieces, McTextures.Scaling.NineSlice nine, int textureW, int textureH,
                                  float x, float y, float w, float h) {
        int spriteW = nine.width();
        int spriteH = nine.height();
        Slicer slicer = new Slicer(pieces, textureW / (float) spriteW, textureH / (float) spriteH, nine.stretchInner());
        float halfW = (float) Math.floor(w / 2f);
        float halfH = (float) Math.floor(h / 2f);
        float left = Math.min(nine.left(), halfW);
        float right = Math.min(nine.right(), halfW);
        float top = Math.min(nine.top(), halfH);
        float bottom = Math.min(nine.bottom(), halfH);
        float middleW = w - left - right;
        float middleH = h - top - bottom;
        float innerW = spriteW - left - right;
        float innerH = spriteH - top - bottom;
        if (w == spriteW && h == spriteH) {
            slicer.blit(0, 0, x, y, w, h);
        } else if (h == spriteH) {
            slicer.blit(0, 0, x, y, left, h);
            slicer.inner(x + left, y, middleW, h, left, 0, innerW, spriteH);
            slicer.blit(spriteW - right, 0, x + w - right, y, right, h);
        } else if (w == spriteW) {
            slicer.blit(0, 0, x, y, w, top);
            slicer.inner(x, y + top, w, middleH, 0, top, spriteW, innerH);
            slicer.blit(0, spriteH - bottom, x, y + h - bottom, w, bottom);
        } else {
            slicer.blit(0, 0, x, y, left, top);
            slicer.inner(x + left, y, middleW, top, left, 0, innerW, top);
            slicer.blit(spriteW - right, 0, x + w - right, y, right, top);
            slicer.blit(0, spriteH - bottom, x, y + h - bottom, left, bottom);
            slicer.inner(x + left, y + h - bottom, middleW, bottom, left, spriteH - bottom, innerW, bottom);
            slicer.blit(spriteW - right, spriteH - bottom, x + w - right, y + h - bottom, right, bottom);
            slicer.inner(x, y + top, left, middleH, 0, top, left, innerH);
            slicer.inner(x + left, y + top, middleW, middleH, left, top, innerW, innerH);
            slicer.inner(x + w - right, y + top, right, middleH, spriteW - right, top, right, innerH);
        }
    }

    // slice coordinates are sprite (gui) pixels; texelsX/Y convert them to texture pixels for hd packs
    private record Slicer(List<Piece> pieces, float texelsX, float texelsY, boolean stretchInner) {
        Slicer(List<Piece> pieces, float texelsX, float texelsY) {
            this(pieces, texelsX, texelsY, false);
        }

        void blit(float sliceX, float sliceY, float x, float y, float w, float h) {
            stretch(sliceX, sliceY, w, h, x, y, w, h);
        }

        void stretch(float sliceX, float sliceY, float sliceW, float sliceH, float x, float y, float w, float h) {
            if (w <= 0f || h <= 0f || sliceW <= 0f || sliceH <= 0f) return;
            pieces.add(new Piece(sliceX * texelsX, sliceY * texelsY, sliceW * texelsX, sliceH * texelsY, x, y, w, h));
        }

        void inner(float x, float y, float w, float h, float sliceX, float sliceY, float sliceW, float sliceH) {
            if (stretchInner) stretch(sliceX, sliceY, sliceW, sliceH, x, y, w, h);
            else tiled(x, y, w, h, sliceX, sliceY, sliceW, sliceH);
        }

        void tiled(float x, float y, float w, float h, float sliceX, float sliceY, float sliceW, float sliceH) {
            if (w <= 0f || h <= 0f || sliceW <= 0f || sliceH <= 0f) return;
            if (Math.ceil(w / sliceW) * Math.ceil(h / sliceH) > MAX_TILES) {
                stretch(sliceX, sliceY, sliceW, sliceH, x, y, w, h);
                return;
            }
            for (float i = 0f; i < w; i += sliceW) {
                float tileW = Math.min(sliceW, w - i);
                for (float j = 0f; j < h; j += sliceH) {
                    blit(sliceX, sliceY, x + i, y + j, tileW, Math.min(sliceH, h - j));
                }
            }
        }
    }
}
