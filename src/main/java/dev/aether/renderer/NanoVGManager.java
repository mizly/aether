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

/**
 * Singleton manager for the NanoVG rendering context.
 *
 * <p>Call {@link #init()} once during client startup (before any rendering),
 * and {@link #destroy()} during shutdown. Every screen frame that wants to use
 * NanoVG should call {@link #beginFrame(float, float)} before drawing and
 * {@link #endFrame()} when finished.</p>
 *
 * <p>Font files are loaded automatically from the mod's resources. Use
 * {@link #getFontId(String)} with a name constant from {@link Fonts}
 * to retrieve the registered font ID.</p>
 */
public final class NanoVGManager {

    // -- Singleton state -------------------------------------------------------

    private static long vg = -1L;
    private static NVGRenderer renderer;
    private static boolean initialized = false;
    private static boolean drawing = false;

    /** Current pixel ratio (physical px per logical px), updated every beginFrame(). */
    private static float pxRatio = 1f;

    /** GL sampler ID bound to unit 0 before we clear it - restored in endFrame(). */
    private static int savedSampler = 0;

    /** GL framebuffer bound when beginFrame() was called - restored in endFrame(). */
    private static int savedFbo = 0;

    /** MC's main render target FBO, resolved each beginFrame(). Used by RippleEffect. */
    private static int mainRtFbo = 0;

    /**
     * When >= 0, {@link #beginFrame} renders into this FBO instead of MC's main RT.
     * Automatically reset to {@code -1} after one use.
     * Set by {@link RippleEffect} to redirect NVG into its offscreen scene buffer.
     */
    private static int overrideTargetFbo = -1;

    /** GL program active when beginFrame() was called - restored in endFrame().
     *  NanoVG's GL3 backend calls glUseProgram(0) internally at the end of nvgEndFrame(),
     *  which would leave MC's pipeline with no active shader. */
    private static int savedProgram = 0;

    /** VAO bound when beginFrame() was called - restored in endFrame().
     *  NanoVG binds its own VAO and does not restore the previous one, which causes
     *  MC's font renderer (debug overlay etc.) to read the wrong vertex state. */
    private static int savedVao = 0;

    /** Maps font name -> NanoVG font ID. */
    private static final Map<String, Integer> fontIds = new HashMap<>();
    /** Keep ByteBuffers alive so NanoVG doesn't read freed memory. */
    private static final Map<String, ByteBuffer> fontBuffers = new HashMap<>();
    private static final String UNICODE_FALLBACK_FONT = "Aether-Unicode-Fallback";

    private NanoVGManager() {}

    // -- Lifecycle -------------------------------------------------------------

    /**
     * Initialises the NanoVG context and loads the built-in fonts.
     * Must be called once on the main render thread before any NVG rendering.
     *
     * @throws RuntimeException if the NanoVG context could not be created
     */
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
        loadUnicodeFallbackFont();

        initialized = true;
    }

    /**
     * Destroys the NanoVG context and releases all resources.
     * Should be called during client shutdown.
     */
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

    /**
     * Begins a new NanoVG frame, binding MC's main render target as the draw target.
     *
     * <p>This must be called before any {@link NVGRenderer} drawing calls and must be
     * paired with exactly one {@link #endFrame()} call.</p>
     *
     * @param width  logical screen width in pixels
     * @param height logical screen height in pixels
     * @throws IllegalStateException if {@code init()} has not been called or a frame is already open
     */
    public static void beginFrame(float width, float height) {
        if (!initialized) throw new IllegalStateException("[Aether] NanoVGManager.init() must be called first");
        if (drawing)      throw new IllegalStateException("[Aether] endFrame() was not called before beginFrame()");

        float computedRatio = 1f;
        try {
            var rt = Minecraft.getInstance().getMainRenderTarget();
            // Bind MC's main render target so NVG draws into the same buffer as the rest of
            // the game. Without this bind the title screen is invisible (NVG writes to FBO=0
            // which is the window framebuffer, but MC blits from the RT's FBO).

            savedFbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int mcFbo = ((GlTexture) rt.getColorTexture()).getFbo(resolveDirectStateAccess(), null);
            mainRtFbo = mcFbo;
            int targetFbo = overrideTargetFbo >= 0 ? overrideTargetFbo : mcFbo;
            overrideTargetFbo = -1;
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
            GlStateManager._viewport(0, 0, rt.width, rt.height);
            computedRatio = (float) rt.width / width;
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[Aether] Failed to bind main render target for NanoVG: " + e.getMessage());
            savedFbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            overrideTargetFbo = -1;
        }

        pxRatio = computedRatio;

        // Save the active GL program. NanoVG's GL3 backend calls glUseProgram(0)
        // internally when it finishes rendering - we restore here so MC's pipeline
        // doesn't lose its active shader between Screen.render() and Gui.render().
        savedProgram = GL11.glGetInteger(0x8B8D /* GL_CURRENT_PROGRAM */);

        // Save the VAO. NanoVG binds its own and never restores the previous one.
        savedVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        // Unbind MC's sampler from unit 0 so NanoVG can bind its font atlas texture.
        GlStateManager._activeTexture(GL30.GL_TEXTURE0);
        savedSampler = GL33C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        GL33C.glBindSampler(0, 0);

        NanoVG.nvgBeginFrame(vg, width, height, pxRatio);
        NanoVG.nvgTextAlign(vg, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_TOP);
        drawing = true;
    }

    /**
     * Ends the current NanoVG frame and flushes all queued drawing commands.
     * Restores the minimum GL state Minecraft expects after NanoVG rendering.
     *
     * @throws IllegalStateException if no frame is currently open
     */
    public static void endFrame() {
        if (!drawing) throw new IllegalStateException("[Aether] beginFrame() was not called before endFrame()");

        NanoVG.nvgEndFrame(vg);

        // Restore GL state expected by Minecraft's rendering pipeline.
        // nvgEndFrame() internally calls glUseProgram(0) - restore MC's shader so
        // anything that runs after (tooltip flush, Gui.render debug overlay, etc.)
        // doesn't hit GL_INVALID_OPERATION from having no active program.
        GlStateManager._glUseProgram(savedProgram);
        GlStateManager._disableCull();
        GlStateManager._disableDepthTest();
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(770, 771, 1, 0);

        GL14.glBlendEquation(GL14.GL_FUNC_ADD);

        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glColorMask(true, true, true, true);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        GlStateManager._activeTexture(GL30.GL_TEXTURE0);
        GlStateManager._bindTexture(0);

        GL33C.glBindSampler(0, savedSampler);

        GL30.glBindVertexArray(savedVao);

        drawing = false;
    }

    // -- Accessors -------------------------------------------------------------

    /** Returns the singleton {@link NVGRenderer} for this context. */
    public static NVGRenderer getRenderer() { return renderer; }

    /** Returns the raw NanoVG context handle ({@code long vg}). */
    public static long getVg() { return vg; }

    /** @return {@code true} if {@link #init()} has been called successfully */
    public static boolean isInitialized() { return initialized; }

    /**
     * Redirects the next {@link #beginFrame} call to render into {@code fbo} instead
     * of MC's main render target. Consumed after one use. Used by {@link RippleEffect}.
     */
    public static void setOverrideTargetFbo(int fbo) { overrideTargetFbo = fbo; }

    /**
     * Returns MC's main render target FBO, resolved during the last {@link #beginFrame}.
     * Valid after the first frame; used by {@link RippleEffect#composite} as the output target.
     */
    public static int getMainRtFbo() { return mainRtFbo; }

    private static DirectStateAccess resolveDirectStateAccess() {
        return ((AccessorGlDevice) ((AccessorGpuDevice) RenderSystem.getDevice()).aether$getBackend())
                .aether$directStateAccess();
    }

    /** @return {@code true} if a frame has been opened with {@link #beginFrame} */
    public static boolean isDrawing() { return drawing; }

    /** Returns the current pixel ratio (physical pixels per logical pixel). Updated each frame. */
    public static float getPxRatio() { return pxRatio; }

    /**
     * Returns the NanoVG font ID registered under {@code name}, or {@code -1}
     * if the font has not been loaded.
     *
     * @param name font name as defined in {@link Fonts}
     */
    public static int getFontId(String name) {
        return fontIds.getOrDefault(name, -1);
    }

    // -- Font loading ----------------------------------------------------------

    /**
     * Loads a font from the mod's resources and registers it with NanoVG.
     *
     * <p>The font's byte buffer is kept in memory for the lifetime of the context
     * because NanoVG holds a raw pointer into it.</p>
     *
     * @param name         the name to register the font under
     * @param resourcePath absolute path within the jar, e.g. {@code "/assets/aether/fonts/Inter-Regular.otf"}
     */
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
                    fontDir + "\\YuGothR.ttc",
                    fontDir + "\\msgothic.ttc",
                    fontDir + "\\msyh.ttc",
                    fontDir + "\\msjh.ttc",
                    fontDir + "\\simsun.ttc",
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
    }

    private static void addFallback(String baseFont, int fallbackId) {
        int baseId = fontIds.getOrDefault(baseFont, -1);
        if (baseId != -1) {
            NanoVG.nvgAddFallbackFontId(vg, baseId, fallbackId);
        }
    }
}

