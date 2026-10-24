package dev.aether.renderer;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.aether.renderer.canvas.Canvas;
import dev.aether.util.AetherResources;
import dev.aether.ui.util.Fonts;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

// init() once at client startup and destroy() at shutdown; every frame pairs beginFrame with endFrame
// frames draw through Canvas on mc's gpu api, so this works on both the opengl and vulkan backends
public final class NanoVGManager {

    // -- Singleton state -------------------------------------------------------

    private static Canvas canvas;
    private static NVGRenderer renderer;
    private static boolean initialized = false;
    private static boolean drawing = false;

    private static float pxRatio = 1f;

    private static GpuTextureView frameTarget;

    private static final String UNICODE_FALLBACK_FONT = "Aether-Unicode-Fallback";

    private NanoVGManager() {}

    // -- Lifecycle -------------------------------------------------------------

    public static boolean ensureInitialized() {
        if (!initialized) init();
        return true;
    }

    // main render thread, once, before any drawing
    public static void init() {
        if (initialized) return;
        canvas = new Canvas();
        renderer = new NVGRenderer(canvas);

        loadFont("Inter-Regular", "/assets/aether/fonts/Inter-Regular.otf");
        loadFont("Inter-Bold",    "/assets/aether/fonts/Inter-Bold.otf");
        loadFont("Inter-Mono",    "/assets/aether/fonts/Inter-Mono.otf");
        loadFont(Fonts.SCOREBOARD_BOLD, "/assets/aether/fonts/scoreboard/Inter-Bold.otf");
        loadUnicodeFallbackFont();

        initialized = true;
    }

    public static void destroy() {
        if (!initialized) return;
        SVGRenderer.destroy();
        renderer.deleteAllImages();
        canvas.close();
        canvas = null;
        renderer = null;
        initialized = false;
        drawing = false;
    }

    // -- Frame lifecycle -------------------------------------------------------

    // draws into mc's main render target; pair with exactly one endFrame()
    public static void beginFrame(float width, float height) {
        if (!initialized) throw new IllegalStateException("[Aether] NanoVGManager.init() must be called first");
        if (drawing)      throw new IllegalStateException("[Aether] endFrame() was not called before beginFrame()");

        GpuTextureView target = mainTarget();
        frameTarget = target;
        pxRatio = target != null && width > 0 ? target.getWidth(0) / width : 1f;

        canvas.beginFrame(width, height, pxRatio);
        renderer.beginFrame();
        drawing = true;
    }

    public static void endFrame() {
        if (!drawing) throw new IllegalStateException("[Aether] beginFrame() was not called before endFrame()");
        try {
            canvas.endFrame(frameTarget);
        } finally {
            frameTarget = null;
            drawing = false;
        }
    }

    // -- Accessors -------------------------------------------------------------

    public static NVGRenderer getRenderer() { return renderer; }

    public static boolean isInitialized() { return initialized; }

    public static GpuTextureView mainTarget() {
        var rt = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return rt != null ? rt.getColorTextureView() : null;
    }

    public static boolean isDrawing() { return drawing; }

    public static float getPxRatio() { return pxRatio; }

    // -1 when the font has not been loaded
    public static int getFontId(String name) {
        return canvas != null ? canvas.fontId(name) : -1;
    }

    // -- Font loading ----------------------------------------------------------

    // the canvas keeps the font bytes alive and frees them on destroy
    public static void loadFont(String name, String resourcePath) {
        if (canvas.fontId(name) != -1) return;
        try (InputStream in = AetherResources.open(resourcePath)) {
            if (in == null) {
                System.err.println("[Aether] Font resource not found: " + resourcePath);
                return;
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            if (canvas.loadFont(name, buffer) == -1) {
                MemoryUtil.memFree(buffer);
                System.err.println("[Aether] Failed to load font: " + name);
            }
        } catch (IOException e) {
            System.err.println("[Aether] IOException loading font " + name + ": " + e.getMessage());
        }
    }

    private static void loadUnicodeFallbackFont() {
        String[] candidates = getUnicodeFallbackCandidates();
        boolean loadedAny = false;
        for (int i = 0; i < candidates.length; i++) {
            String candidate = candidates[i];
            int id = loadFontFromFile(UNICODE_FALLBACK_FONT + "-" + i, candidate);
            if (id != -1) {
                attachFallbackToUiFonts(id);
                loadedAny = true;
            }
        }

        if (!loadedAny) {
            System.err.println("[Aether] No Unicode fallback font found; some glyphs may render as missing.");
        }
    }

    private static String[] getUnicodeFallbackCandidates() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            String winDir = System.getenv("WINDIR");
            String fontDir = (winDir == null || winDir.isBlank()) ? "C:\\Windows\\Fonts" : winDir + "\\Fonts";
            return new String[] {
                    fontDir + "\\msyh.ttc",
                    fontDir + "\\msjh.ttc",
                    fontDir + "\\simsun.ttc",
                    fontDir + "\\YuGothR.ttc",
                    fontDir + "\\msgothic.ttc",
                    fontDir + "\\malgun.ttf",
                    fontDir + "\\seguisym.ttf",
                    fontDir + "\\seguiemj.ttf",
                    fontDir + "\\arial.ttf"
            };
        }
        if (osName.contains("mac")) {
            return new String[] {
                    "/System/Library/Fonts/PingFang.ttc",
                    "/System/Library/Fonts/\u30d2\u30e9\u30ae\u30ce\u89d2\u30b4\u30b7\u30c3\u30af W3.ttc",
                    "/System/Library/Fonts/Hiragino Sans GB.ttc",
                    "/System/Library/Fonts/AppleSDGothicNeo.ttc",
                    "/System/Library/Fonts/Apple Symbols.ttf",
                    "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                    "/System/Library/Fonts/Supplemental/Arial.ttf"
            };
        }
        return new String[] {
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
                "/usr/share/fonts/opentype/noto/NotoSansCJKtc-Regular.otf",
                "/usr/share/fonts/opentype/noto/NotoSansCJKkr-Regular.otf",
                "/usr/share/fonts/google-noto-cjk/NotoSansCJKjp-Regular.otf",
                "/usr/share/fonts/google-noto-cjk/NotoSansCJKsc-Regular.otf",
                "/usr/share/fonts/google-noto-cjk/NotoSansCJKtc-Regular.otf",
                "/usr/share/fonts/google-noto-cjk/NotoSansCJKkr-Regular.otf",
                "/usr/share/fonts/opentype/noto-cjk/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/noto/NotoSansSymbols2-Regular.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansSymbols2-Regular.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
        };
    }

    private static int loadFontFromFile(String name, String filePath) {
        int existing = canvas.fontId(name);
        if (existing != -1) return existing;

        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) return -1;

        try {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            int id = canvas.loadFont(name, buffer);
            if (id == -1) {
                MemoryUtil.memFree(buffer);
                System.err.println("[Aether] Failed to load font: " + filePath);
            }
            return id;
        } catch (IOException e) {
            System.err.println("[Aether] IOException loading font " + filePath + ": " + e.getMessage());
            return -1;
        }
    }

    private static void attachFallbackToUiFonts(int fallbackId) {
        if (fallbackId == -1) return;
        addFallback(Fonts.REGULAR, fallbackId);
        addFallback(Fonts.BOLD, fallbackId);
        addFallback(Fonts.MONO, fallbackId);
        addFallback(Fonts.SCOREBOARD_BOLD, fallbackId);
    }

    private static void addFallback(String baseFont, int fallbackId) {
        int baseId = canvas.fontId(baseFont);
        if (baseId != -1) {
            canvas.addFallbackFont(baseId, fallbackId);
        }
    }
}
