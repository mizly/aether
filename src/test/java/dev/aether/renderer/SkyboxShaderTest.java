package dev.aether.renderer;

import dev.aether.modules.visuals.Skybox;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AETHER_TEST_OPENGL", matches = "1")
class SkyboxShaderTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static long window;
    private static GLFWErrorCallback errors;
    private SkyboxShader shader;

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
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "Aether sky shader tests", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    @AfterAll
    static void destroyContext() {
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null);
        errors.free();
    }

    @BeforeEach
    void createRenderer() {
        shader = new SkyboxShader();
        clear();
    }

    @AfterEach
    void closeRenderer() {
        shader.close();
    }

    @Test
    void rendersDistinctOpaqueSkiesAndMeasuresGpuTimeAt1080p() throws Exception {
        Matrix4f view = inverseView(0, -0.22f, WIDTH / (float) HEIGHT);
        var fingerprints = new HashSet<Integer>();
        int query = GL15.glGenQueries();
        try {
            for (int preset = 0; preset < Skybox.PRESETS.size(); preset++) {
                assertTrue(shader.draw(view, preset, 35, 1, 0, WIDTH, HEIGHT));
                ByteBuffer pixels = readPixels();
                int[] rgb = new int[WIDTH * HEIGHT];
                for (int i = 0; i < rgb.length; i++) {
                    assertEquals(255, pixels.get(i * 4 + 3) & 255);
                    rgb[i] = (pixels.get(i * 4) & 255) << 16 | (pixels.get(i * 4 + 1) & 255) << 8
                            | pixels.get(i * 4 + 2) & 255;
                    assertNotEquals(0xFF00FF, rgb[i], "The sky must cover every pixel");
                }
                fingerprints.add(Arrays.hashCode(rgb));
                capture(Skybox.PRESETS.get(preset).toLowerCase().replace(' ', '-'), rgb);
                long[] samples = new long[60];
                long[] submissions = new long[samples.length];
                for (int frame = -20; frame < samples.length; frame++) {
                    GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
                    long start = System.nanoTime();
                    assertTrue(shader.draw(view, preset, 35 + frame * 0.016f, 1, 0, WIDTH, HEIGHT));
                    long submission = System.nanoTime() - start;
                    GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                    long elapsed = GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT);
                    if (frame >= 0) {
                        samples[frame] = elapsed;
                        submissions[frame] = submission;
                    }
                }
                Arrays.sort(samples);
                Arrays.sort(submissions);
                System.out.printf("Skybox %s at 1920x1080: GPU median %.3f ms, p95 %.3f ms, CPU submission median %.3f ms (%s)%n",
                        Skybox.PRESETS.get(preset), samples[30] / 1e6, samples[57] / 1e6,
                        submissions[30] / 1e6,
                        GL11.glGetString(GL11.GL_RENDERER));
            }
        } finally {
            GL15.glDeleteQueries(query);
        }
        assertEquals(Skybox.PRESETS.size(), fingerprints.size());
        assertFalse(shader.isFailed());
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void restoresGraphicsStateAndLeavesWorldDepthUntouchedIncludingFirstDraw() {
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        GL11.glDepthFunc(GL11.GL_GREATER);
        GL11.glDepthMask(true);
        GL11.glColorMask(false, true, false, false);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        GL11.glViewport(3, 4, 80, 90);
        try {
            assertTrue(shader.draw(inverseView(0, 0, 16f / 9), 0, 0, 1, 0, WIDTH, HEIGHT));
            for (int capability : new int[]{GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE,
                    GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST, GL30.GL_RASTERIZER_DISCARD}) {
                assertTrue(GL11.glIsEnabled(capability));
            }
            assertTrue(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            assertEquals(GL11.GL_GREATER, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
            assertEquals(vao, GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));
            assertEquals(0, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
            assertEquals(0, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var polygonMode = stack.mallocInt(2);
                GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, polygonMode);
                assertEquals(GL11.GL_LINE, polygonMode.get(0));
                var viewport = stack.mallocInt(4);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                assertEquals(3, viewport.get(0));
                assertEquals(90, viewport.get(3));
                var mask = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                assertEquals(0, mask.get(0));
                assertEquals(1, mask.get(1));
                assertEquals(0, mask.get(3));
                var depth = stack.mallocFloat(1);
                GL11.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
                assertEquals(0.375f, depth.get(0), 0.000001f);
            }
            assertNotEquals(255, readPixels().get(1) & 255);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GL30.glBindVertexArray(0);
            GL30.glDeleteVertexArrays(vao);
            clear();
        }
    }

    @Test
    void handlesPolesWideViewsResizeAndReinitialization() {
        for (float pitch : new float[]{-1.5707f, 0, 1.5707f}) {
            for (int preset = 0; preset < Skybox.PRESETS.size(); preset++) {
                assertTrue(shader.draw(inverseView(2.2f, pitch, 32f / 9), preset, 86400, 1.5f, 0, 960, 270));
            }
        }
        shader.close();
        assertTrue(shader.draw(inverseView(0, 0, 1), 4, 0, 0.5f, 0, 540, 540));
        assertFalse(shader.draw(new Matrix4f(), -1, 0, 1, 0, WIDTH, HEIGHT));
        assertFalse(shader.draw(new Matrix4f(), 0, 0, 1, 0, 0, 0));
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private static Matrix4f inverseView(float yaw, float pitch, float aspect) {
        return new Matrix4f().perspective((float) Math.toRadians(75), aspect, 0.05f, 1024)
                .rotateX(pitch).rotateY(yaw).invert();
    }

    private static void clear() {
        for (int capability : new int[]{GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE,
                GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST, GL30.GL_RASTERIZER_DISCARD}) {
            GL11.glDisable(capability);
        }
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        GL11.glClearColor(1, 0, 1, 0);
        GL11.glClearDepth(0.375);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    private static ByteBuffer readPixels() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
        GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static void capture(String name, int[] rgb) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            image.setRGB(0, HEIGHT - y - 1, WIDTH, 1, rgb, y * WIDTH, WIDTH);
        }
        Path directory = Path.of("build/reports/skybox");
        Files.createDirectories(directory);
        ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
    }
}
