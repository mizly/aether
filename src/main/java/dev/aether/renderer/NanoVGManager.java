package dev.aether.renderer;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.mixin.AccessorGlDevice;
import dev.aether.mixin.AccessorGpuDevice;
import dev.aether.util.AetherResources;
import dev.aether.ui.util.Fonts;
import net.minecraft.client.Minecraft;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33C;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// init() once at client startup and destroy() at shutdown; every frame pairs beginFrame with endFrame
// fonts load from the mod's resources - look them up with getFontId and a Fonts constant
public final class NanoVGManager {

    // -- Singleton state -------------------------------------------------------

    private static long vg = -1L;
    private static NVGRenderer renderer;
    private static boolean initialized = false;
    private static boolean drawing = false;

    private static float pxRatio = 1f;

    // restored in endFrame()
    private static int savedSampler = 0;

    // restored in endFrame()
    private static int savedFbo = 0;
    private static int savedReadFbo;
    private static final int[] savedViewport = new int[4];
    private static final ByteBuffer savedColorMask = ByteBuffer.allocateDirect(4);
    private static boolean savedDepthTest;
    private static boolean savedCull;
    private static boolean savedBlend;
    private static boolean savedScissor;
    private static int savedSrcRgb, savedDstRgb, savedSrcAlpha, savedDstAlpha;
    private static int savedEquationRgb, savedEquationAlpha;
    private static int savedActiveTexture, savedTexture;

    // resolved each beginFrame(); used by RippleEffect
    private static int mainRtFbo = 0;

    // reset to -1 after one use; RippleEffect sets it to redirect nvg into its offscreen scene buffer
    private static int overrideTargetFbo = -1;

    // nanovg's gl3 backend calls glUseProgram(0) at the end of nvgEndFrame, which would leave mc's pipeline with no shader
    private static int savedProgram = 0;

    // nanovg binds its own vao and never restores the previous one, which makes mc's font renderer read the wrong vertex state
    private static int savedVao = 0;

    private static final Map<String, Integer> fontIds = new HashMap<>();
    // keeps the ByteBuffers alive so nanovg doesn't read freed memory
    private static final Map<String, ByteBuffer> fontBuffers = new HashMap<>();
    private static final String UNICODE_FALLBACK_FONT = "Aether-Unicode-Fallback";

    private NanoVGManager() {}

    // -- Lifecycle -------------------------------------------------------------

    // main render thread, once, before any nvg rendering
    public static void init() {
        if (initialized) return;

        vg = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS | NanoVGGL3.NVG_STENCIL_STROKES);
        if (vg == -1L) {
            throw new RuntimeException("[Aether] Failed to create NanoVG context");
        }

        renderer = new NVGRenderer(vg);

        // Load bundled fonts
        loadFont("Inter-Regular", "/assets/aether/fonts/Inter-Regular.otf");
        loadFont("Inter-Bold",    "/assets/aether/fonts/Inter-Bold.otf");
        loadFont("Inter-Mono",    "/assets/aether/fonts/Inter-Mono.otf");
        loadFont(Fonts.SCOREBOARD_BOLD, "/assets/aether/fonts/scoreboard/Inter-Bold.otf");
        loadUnicodeFallbackFont();

        initialized = true;
    }

    public static void destroy() {
        if (!initialized) return;
        SVGRenderer.destroy(vg);
        NanoVGGL3.nvgDelete(vg);
        vg = -1L;
        renderer = null;
        fontIds.clear();
        fontBuffers.clear();
        initialized = false;
        drawing = false;
    }

    // -- Frame lifecycle -------------------------------------------------------

    // binds mc's main render target; pair with exactly one endFrame()
    public static void beginFrame(float width, float height) {
        if (!initialized) throw new IllegalStateException("[Aether] NanoVGManager.init() must be called first");
        if (drawing)      throw new IllegalStateException("[Aether] endFrame() was not called before beginFrame()");

        saveFrameState();
        float computedRatio = 1f;
        try {
            var rt = Minecraft.getInstance().getMainRenderTarget();
            // Bind MC's main render target so NVG draws into the same buffer as the rest of
            // the game. Without this bind the title screen is invisible (NVG writes to FBO=0
            // which is the window framebuffer, but MC blits from the RT's FBO).

            int mcFbo = ((GlTexture) rt.getColorTexture()).getFbo(resolveDirectStateAccess(), null);
            mainRtFbo = mcFbo;
            int targetFbo = overrideTargetFbo >= 0 ? overrideTargetFbo : mcFbo;
            overrideTargetFbo = -1;
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
            GlStateManager._viewport(0, 0, rt.width, rt.height);
            computedRatio = (float) rt.width / width;
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[Aether] Failed to bind main render target for NanoVG: " + e.getMessage());
            overrideTargetFbo = -1;
        }

        pxRatio = computedRatio;

        // Unbind MC's sampler from unit 0 so NanoVG can bind its font atlas texture.
        GL33C.glBindSampler(0, 0);

        renderer.beginMinecraftTextFrame();
        GlStateManager._disableDepthTest();
        NanoVG.nvgBeginFrame(vg, width, height, pxRatio);
        NanoVG.nvgTextAlign(vg, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_TOP);
        drawing = true;
    }

    public static void endFrame() {
        if (!drawing) throw new IllegalStateException("[Aether] beginFrame() was not called before endFrame()");

        try {
            NanoVG.nvgEndFrame(vg);
        } finally {
            try {
                renderer.endMinecraftTextFrame();
            } finally {
                restoreFrameState();
            }
        }
    }

    private static void saveFrameState() {
        savedFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        savedReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, savedColorMask);
        savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        savedCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        savedScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        savedSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        savedDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        savedSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        savedDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        savedEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        savedEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        savedActiveTexture = GL11.glGetInteger(GL30.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL30.GL_TEXTURE0);
        savedTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        savedSampler = GL11.glGetInteger(GL33C.GL_SAMPLER_BINDING);
    }

    private static void restoreFrameState() {
        GlStateManager._glUseProgram(savedProgram);
        if (savedDepthTest) GlStateManager._enableDepthTest();
        else GlStateManager._disableDepthTest();

        // NanoVG bypasses Minecraft's cache; restore these without a cached setter skipping the GL call.
        restoreCapability(GL11.GL_CULL_FACE, savedCull);
        restoreCapability(GL11.GL_BLEND, savedBlend);
        restoreCapability(GL11.GL_SCISSOR_TEST, savedScissor);
        GL14.glBlendFuncSeparate(savedSrcRgb, savedDstRgb, savedSrcAlpha, savedDstAlpha);
        GL20.glBlendEquationSeparate(savedEquationRgb, savedEquationAlpha);

        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glColorMask(savedColorMask.get(0) != 0, savedColorMask.get(1) != 0,
                savedColorMask.get(2) != 0, savedColorMask.get(3) != 0);

        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedReadFbo);
        GlStateManager._viewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTexture);

        GL33C.glBindSampler(0, savedSampler);
        GlStateManager._activeTexture(savedActiveTexture);

        GL30.glBindVertexArray(savedVao);

        drawing = false;
    }

    private static void restoreCapability(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    // -- Accessors -------------------------------------------------------------

    public static NVGRenderer getRenderer() { return renderer; }

    public static long getVg() { return vg; }

    public static boolean isInitialized() { return initialized; }

    // consumed after one use; used by RippleEffect
    public static void setOverrideTargetFbo(int fbo) { overrideTargetFbo = fbo; }

    // valid after the first frame; RippleEffect.composite uses it as the output target
    public static int getMainRtFbo() { return mainRtFbo; }

    private static DirectStateAccess resolveDirectStateAccess() {
        return ((AccessorGlDevice) ((AccessorGpuDevice) RenderSystem.getDevice()).aether$getBackend())
                .aether$directStateAccess();
    }

    public static boolean isDrawing() { return drawing; }

    public static float getPxRatio() { return pxRatio; }

    // -1 when the font has not been loaded
    public static int getFontId(String name) {
        return fontIds.getOrDefault(name, -1);
    }

    // -- Font loading ----------------------------------------------------------

    // the byte buffer stays in memory for the lifetime of the context, because nanovg holds a raw pointer into it
    public static void loadFont(String name, String resourcePath) {
        if (fontIds.containsKey(name)) return;
        try (InputStream in = AetherResources.open(resourcePath)) {
            if (in == null) {
                System.err.println("[Aether] Font resource not found: " + resourcePath);
                return;
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length)
                    .order(ByteOrder.nativeOrder())
                    .put(bytes);
            buffer.flip();

            int id = NanoVG.nvgCreateFontMem(vg, name, buffer, false);
            if (id == -1) {
                System.err.println("[Aether] NanoVG failed to load font: " + name);
                return;
            }
            fontIds.put(name, id);
            fontBuffers.put(name, buffer); // prevent GC
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
        if (fontIds.containsKey(name)) return fontIds.getOrDefault(name, -1);

        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) return -1;

        try {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length)
                    .order(ByteOrder.nativeOrder())
                    .put(bytes);
            buffer.flip();

            int id = NanoVG.nvgCreateFontMem(vg, name, buffer, false);
            if (id == -1) {
                System.err.println("[Aether] NanoVG failed to load font: " + filePath);
                return -1;
            }
            fontIds.put(name, id);
            fontBuffers.put(name, buffer);
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
        int baseId = fontIds.getOrDefault(baseFont, -1);
        if (baseId != -1) {
            NanoVG.nvgAddFallbackFontId(vg, baseId, fallbackId);
        }
    }
}
