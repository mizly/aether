package dev.aether.renderer.canvas;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

// shared rgba pages for glyphs, svg rasters and the white block solid fills sample from
final class UiAtlas {
    static final int SIZE = 2048;
    private static final int PAD = 2;
    // more pages than this get dropped at the start of a frame, never mid-frame
    private static final int MAX_PAGES = 3;
    private static final int WHITE = 4;

    private final List<Page> pages = new ArrayList<>();
    private int generation;

    record Region(Page page, float u0, float v0, float u1, float v1, int width, int height) {}

    static final class Page {
        final GpuTexture texture;
        final GpuTextureView view;
        final float whiteU;
        final float whiteV;
        private int shelfX = WHITE + PAD;
        private int shelfY;
        private int shelfHeight = WHITE;

        private Page(GpuDevice device, int index) {
            texture = device.createTexture(() -> "Aether UI atlas " + index,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            view = device.createTextureView(texture);
            ByteBuffer white = MemoryUtil.memAlloc(WHITE * WHITE * 4);
            try {
                for (int i = 0; i < WHITE * WHITE * 4; i++) white.put(i, (byte) 0xFF);
                device.createCommandEncoder().writeToTexture(texture, white, 0, 0, 0, 0, WHITE, WHITE);
            } finally {
                MemoryUtil.memFree(white);
            }
            whiteU = (WHITE / 2f) / SIZE;
            whiteV = (WHITE / 2f) / SIZE;
        }

        private int[] place(int w, int h) {
            if (shelfX + w + PAD > SIZE) {
                shelfY += shelfHeight + PAD;
                shelfX = 0;
                shelfHeight = 0;
            }
            if (shelfY + h + PAD > SIZE || w + PAD > SIZE) return null;
            int[] at = {shelfX, shelfY};
            shelfX += w + PAD;
            shelfHeight = Math.max(shelfHeight, h);
            return at;
        }

        void close() {
            view.close();
            texture.close();
        }
    }

    GpuSampler sampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    // anything cached against an older generation points at a page that may be gone
    int generation() {
        return generation;
    }

    Page currentPage() {
        if (pages.isEmpty()) pages.add(new Page(RenderSystem.getDevice(), 0));
        return pages.getLast();
    }

    void beginFrame() {
        if (pages.size() > MAX_PAGES) reset();
    }

    void reset() {
        pages.forEach(Page::close);
        pages.clear();
        generation++;
    }

    // rgba is tightly packed, width * height * 4 bytes
    Region upload(ByteBuffer rgba, int width, int height) {
        if (width <= 0 || height <= 0 || width > SIZE - PAD || height > SIZE - PAD) return null;
        Page page = currentPage();
        int[] at = page.place(width, height);
        if (at == null) {
            page = new Page(RenderSystem.getDevice(), pages.size());
            pages.add(page);
            at = page.place(width, height);
            if (at == null) return null;
        }
        RenderSystem.getDevice().createCommandEncoder()
                .writeToTexture(page.texture, rgba, 0, 0, at[0], at[1], width, height);
        return new Region(page, (float) at[0] / SIZE, (float) at[1] / SIZE,
                (float) (at[0] + width) / SIZE, (float) (at[1] + height) / SIZE, width, height);
    }

    void close() {
        reset();
    }
}
