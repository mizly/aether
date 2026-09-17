package dev.aether.renderer;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AETHER_TEST_OPENGL", matches = "1")
class NanoVGManagerTest {
    private static long window;
    private static GLFWErrorCallback errors;
    private static int program;
    private static int vao;

    @BeforeAll
    static void createContext() {
        errors = GLFWErrorCallback.createPrint(System.err);
        GLFW.glfwSetErrorCallback(errors);
        if (System.getenv("DISPLAY") != null && System.getProperty("os.name").equals("Linux")) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        }
        assertTrue(GLFW.glfwInit());
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        window = GLFW.glfwCreateWindow(64, 64, "Aether NVG state test", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
        NanoVGManager.init();

        int vertex = compile(GL20.GL_VERTEX_SHADER, """
                #version 330 core
                uniform float Depth;
                void main() {
                    vec2 positions[3] = vec2[3](vec2(-1, -1), vec2(3, -1), vec2(-1, 3));
                    gl_Position = vec4(positions[gl_VertexID], Depth, 1);
                }
                """);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, """
                #version 330 core
                uniform vec4 Tint;
                out vec4 color;
                void main() { color = Tint; }
                """);
        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS));
        vao = GL30.glGenVertexArrays();
    }

    @AfterAll
    static void destroyContext() {
        NanoVGManager.destroy();
        if (program != 0) GL20.glDeleteProgram(program);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null);
        if (errors != null) errors.free();
    }

    @BeforeEach
    void prepareVanillaState() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(0);
        GL33.glBindSampler(0, 0);
        GlStateManager._enableScissorTest();
        GlStateManager._disableScissorTest();
        GlStateManager._enableCull();
        GlStateManager._disableCull();
        GlStateManager._enableDepthTest();
        GlStateManager._disableDepthTest();
        GlStateManager._disableBlend();
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GlStateManager._depthMask(false);
        GlStateManager._depthMask(true);
        GlStateManager._depthFunc(GL11.GL_LEQUAL);
        GlStateManager._colorMask(0);
        GlStateManager._colorMask(15);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glViewport(0, 0, 64, 64);
        GL11.glClearDepth(0.25);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);
    }

    @Test
    void preservesDepthForSubsequentDraws() {
        GlStateManager._enableDepthTest();
        renderOverlay();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var depth = stack.mallocFloat(1);
            GL11.glReadPixels(32, 32, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            assertEquals(0.25f, depth.get(0), 0.00001f, "NVG must leave existing depth untouched");
        }
        drawVanilla(0.5f, 1);
        assertPixel(32, 32, 0, 0, 255);
        assertTrue(GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        assertTrue(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        assertEquals(GL11.GL_LEQUAL, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void preservesCachedBlendingAndScissorAcrossRepeatedFrames() {
        GlStateManager._enableCull();
        GlStateManager._enableScissorTest();
        GlStateManager._scissorBox(16, 16, 32, 32);
        for (int frame = 0; frame < 3; frame++) {
            renderOverlay();
            GlStateManager._enableBlend();
            GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager._enableScissorTest();
            drawVanilla(0, 0.5f);
            assertPixel(32, 32, 128, 0, 128);
            assertPixel(8, 8, 0, 0, 255);
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            assertFalse(GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void restoresDistinctFramebufferBindingsTextureUnitAndWriteMask() {
        int readFbo = GL30.glGenFramebuffers();
        int texture = GL11.glGenTextures();
        int sampler = GL33.glGenSamplers();
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GlStateManager._bindTexture(texture);
            GL33.glBindSampler(0, sampler);
            GlStateManager._activeTexture(GL13.GL_TEXTURE3);
            GL11.glViewport(3, 4, 48, 52);
            GlStateManager._colorMask(5);
            GlStateManager._disableBlend();
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_SUBTRACT, GL14.GL_FUNC_REVERSE_SUBTRACT);
            renderOverlay();

            assertEquals(readFbo, GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
            assertEquals(0, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
            assertEquals(GL13.GL_TEXTURE3, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(texture);
            assertEquals(texture, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
            assertEquals(sampler, GL11.glGetInteger(GL33.GL_SAMPLER_BINDING));
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertEquals(GL14.GL_FUNC_SUBTRACT, GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB));
            assertEquals(GL14.GL_FUNC_REVERSE_SUBTRACT, GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA));
            assertEquals(program, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
            assertEquals(vao, GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            assertArrayEquals(new int[]{3, 4, 48, 52}, viewport);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                assertEquals(1, mask.get(0));
                assertEquals(0, mask.get(1));
                assertEquals(1, mask.get(2));
                assertEquals(0, mask.get(3));
            }
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(0);
            GL33.glBindSampler(0, 0);
            GL30.glDeleteFramebuffers(readFbo);
            GL11.glDeleteTextures(texture);
            GL33.glDeleteSamplers(sampler);
        }
    }

    @Test
    void keepsMinecraftViewportCacheInSync() {
        GlStateManager._viewport(0, 0, 64, 64);
        GL11.glViewport(3, 4, 48, 52);

        renderOverlay();
        GlStateManager._viewport(0, 0, 64, 64);

        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        assertArrayEquals(new int[]{0, 0, 64, 64}, viewport);
    }

    @Test
    void keepsMinecraftCacheInSyncAfterRippleComposite() {
        var ripple = new RippleEffect();
        try {
            ripple.ensureReady(64, 64);
            GlStateManager._enableDepthTest();
            ripple.composite(0, 64, 64, new float[]{32}, new float[]{32}, new float[]{8},
                    new float[]{2}, new float[]{0}, 1, 1);
            assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
            GlStateManager._enableDepthTest();
            assertTrue(GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            ripple.destroy();
        }
    }

    private static void renderOverlay() {
        NanoVGManager.beginFrame(64, 64);
        try {
            NanoVGManager.getRenderer().rect(0, 0, 64, 64, 0xFF0000FF);
        } finally {
            NanoVGManager.endFrame();
        }
    }

    private static void drawVanilla(float depth, float alpha) {
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "Depth"), depth);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "Tint"), 1, 0, 0, alpha);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private static void assertPixel(int x, int y, int red, int green, int blue) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pixel = stack.malloc(4);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            assertEquals(red, pixel.get(0) & 255, 1);
            assertEquals(green, pixel.get(1) & 255, 1);
            assertEquals(blue, pixel.get(2) & 255, 1);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        assertEquals(GL11.GL_TRUE, GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS), GL20.glGetShaderInfoLog(shader));
        return shader;
    }
}
