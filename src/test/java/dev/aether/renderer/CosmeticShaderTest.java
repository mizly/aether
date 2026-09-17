package dev.aether.renderer;

import dev.aether.modules.visuals.DefeatEffectPool;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AETHER_TEST_OPENGL", matches = "1")
class CosmeticShaderTest {
    private static final int WIDTH = 960, HEIGHT = 640;
    private static long window;
    private static GLFWErrorCallback errors;
    private CosmeticShader shader;
    private CosmeticMesh mesh;

    @BeforeAll
    static void createContext() {
        errors = GLFWErrorCallback.createPrint(System.err);
        GLFW.glfwSetErrorCallback(errors);
        if (System.getenv("DISPLAY") != null && System.getProperty("os.name").equals("Linux"))
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        assertTrue(GLFW.glfwInit());
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "Aether cosmetics", 0, 0);
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

    @BeforeEach void createRenderer() { shader = new CosmeticShader(); mesh = new CosmeticMesh(); }
    @AfterEach void destroyRenderer() { shader.close(); }

    @Test
    void allShaderStylesRenderAndFadeWithoutLeakingGraphicsState() throws Exception {
        for (int style = 0; style < 3; style++) {
            clear();
            mesh.clear();
            PestDefeatMesh.append(mesh, new DefeatEffectPool.Burst(Vec3.ZERO, style, 1, 24, 0.7f, 0),
                    new Vec3(0, 0, 4), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), 350_000_000L);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_GREATER);
            GL11.glViewport(3, 4, 80, 90);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ZERO, GL11.GL_ONE);
            shader.draw(mesh, perspective(), 0, WIDTH, HEIGHT, 0);
            assertFalse(shader.isFailed());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            assertFalse(GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
            assertTrue(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            assertEquals(GL11.GL_GREATER, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
            assertEquals(GL11.GL_ONE, GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB));
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var viewport = stack.mallocInt(4);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                assertEquals(3, viewport.get(0));
                assertEquals(90, viewport.get(3));
            }
            BufferedImage rendered = capture("pest-effect-" + style);
            assertTrue(coloredPixels(rendered) > 1200, "Each style must be visibly rendered");
            clear();
            mesh.clear();
            PestDefeatMesh.append(mesh, new DefeatEffectPool.Burst(Vec3.ZERO, style, 1, 24, 0.7f, 0),
                    new Vec3(0, 0, 4), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), DefeatEffectPool.DURATION_NANOS);
            shader.draw(mesh, perspective(), 0, WIDTH, HEIGHT, 0);
            assertEquals(0, coloredPixels(capture(null)), "Expired effects leave no residual pixels");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        }
    }

    @Test
    void effectsRespectWorldDepthAndDoNotWriteIntoIt() {
        clear();
        var right = new Vector3f(1, 0, 0);
        var up = new Vector3f(0, 1, 0);
        mesh.billboard(0, 0, -2, right, up, 3, 0xFF28303A, 1, 1, 0, 0);
        PestDefeatMesh.append(mesh, new DefeatEffectPool.Burst(Vec3.ZERO, 2, 1, 24, 0, 0),
                new Vec3(0, 0, 4), right, up, 350_000_000L);
        shader.draw(mesh, perspective(), 0, WIDTH, HEIGHT, 6);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pixel = stack.malloc(4);
            GL11.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            assertEquals(40, pixel.get(0) & 255, "The wall occludes the effect");
            clear();
            shader.draw(mesh, perspective(), 0, WIDTH, HEIGHT, 0);
            var depth = stack.mallocFloat(1);
            GL11.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            assertEquals(1f, depth.get(0), "Cosmetic particles must not occlude later world rendering");
        }
    }

    @Test
    void haloStylesRenderFromAboveAndEdgeOnWithOptionalGlow() throws Exception {
        var halo = new HaloMesh();
        for (int style = 0; style < 3; style++) {
            for (int angle = 0; angle < 3; angle++) {
                for (int glow = 0; glow <= 1; glow++) {
                    clear();
                    mesh.clear();
                    var head = new Matrix4f().translation(0, 0, -3)
                            .rotateX(angle * (float) Math.PI / 4).rotateZ(0.14f);
                    halo.append(mesh, head, style, 0.6f, 0xFFFFEAC2, glow);
                    shader.draw(mesh, perspective(), 0, WIDTH, HEIGHT, 0);
                    assertFalse(shader.isFailed());
                    assertTrue(coloredPixels(capture("halo-" + style + "-angle-" + angle + "-glow-" + glow)) > 80,
                            "Halos must remain visible from the side, with and without glow");
                    assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
                }
            }
        }
    }

    @Test
    void edgeOnHaloDoesNotGrowBrightSpikesAtTheSilhouette() throws Exception {
        var halo = new HaloMesh();
        for (int frame = -4; frame <= 4; frame++) {
            clear();
            mesh.clear();
            var head = new Matrix4f().translation(0, 0, -1.2f).rotateX(frame * 0.0005f);
            halo.append(mesh, head, 0, 0, 0xFFFFFFFF, 1);
            shader.draw(mesh, perspective(), 0, WIDTH, HEIGHT, 0);
            BufferedImage rendered = capture("halo-silhouette-" + frame);
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                if ((rendered.getRGB(x, y) & 255) > 150) {
                    assertTrue(Math.abs(y - HEIGHT / 2) <= 8,
                            "Bright spike at frame " + frame + ", pixel " + x + ", " + y);
                }
            }
        }
    }

    @Test
    void haloRespectsOcclusionAndLeavesWorldDepthUnchanged() throws Exception {
        var halo = new HaloMesh();
        var head = new Matrix4f().translation(0, 0, -3).rotateX(0.5f);
        var depth = ByteBuffer.allocateDirect(WIDTH * HEIGHT * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int style = 0; style < 3; style++) {
            clear();
            mesh.clear();
            mesh.billboard(0, 0, -2, new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), 3,
                    0xFF080808, 1, 1, 0, 0);
            halo.append(mesh, head, style, 0, 0xFFFFEAC2, 1);
            shader.draw(mesh, perspective(), 0, WIDTH, HEIGHT, 6);
            assertEquals(0, coloredPixels(capture(null)), "A wall must completely hide every halo style");
            clear();
            mesh.clear();
            halo.append(mesh, head, style, 0, 0xFFFFEAC2, 1);
            shader.draw(mesh, perspective(), 0, WIDTH, HEIGHT, 0);
            depth.clear();
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            while (depth.hasRemaining()) assertEquals(1f, depth.get());
        }
    }

    @Test
    void previewsArticulatedWingsAndReportsMaximumLoadTiming() throws Exception {
        var wings = new DragonWingMesh();
        var halo = new HaloMesh();
        var body = new Matrix4f();
        var view = perspective().lookAt(3.4f, 2.7f, -6.5f, 0, 1.1f, 0, 0, 1, 0);
        for (int frame = 0; frame < 8; frame++) {
            clear();
            mesh.clear();
            boolean wireframe = frame >= 4;
            wings.append(mesh, body, (frame % 4) * (float) Math.PI / 2, 0.36f, 0.25f, 0xFFB080F5, true, wireframe);
            avatar();
            shader.draw(mesh, view, 0, WIDTH, HEIGHT, mesh.size());
            assertFalse(shader.isFailed());
            assertTrue(coloredPixels(capture((wireframe ? "dragon-wings-wireframe-" : "dragon-wings-") + frame % 4)) > 4000);
        }
        long[] cpu = new long[80], submit = new long[80], gpu = new long[80];
        var right = new Vector3f(1, 0, 0);
        var up = new Vector3f(0, 1, 0);
        var bursts = new DefeatEffectPool.Burst[8];
        var benchmarkBody = new Matrix4f().translation(0, -0.8f, -6);
        var benchmarkHead = new Matrix4f().translation(0, 1.4f, -6);
        var benchmarkView = perspective();
        var benchmarkCamera = new Vec3(0, 0, 6);
        for (int i = 0; i < bursts.length; i++)
            bursts[i] = new DefeatEffectPool.Burst(new Vec3((i % 4 - 1.5) * 0.5, i / 4f, 0), i % 3, 2, 24, i, 0);
        int query = GL15.glGenQueries();
        try {
            for (int frame = -80; frame < cpu.length; frame++) {
                clear();
                long start = System.nanoTime();
                mesh.clear();
                wings.append(mesh, benchmarkBody, frame * 0.03f, 0.4f, 0.25f, 0xFFB080F5, true);
                int wingVertices = mesh.size();
                halo.append(mesh, benchmarkHead, 1, frame * 0.03f, 0xFFFFEAC2, 1);
                for (var burst : bursts) PestDefeatMesh.append(mesh, burst, benchmarkCamera, right, up, 350_000_000L);
                long elapsed = System.nanoTime() - start;
                long drawStart = System.nanoTime();
                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
                shader.draw(mesh, benchmarkView, 0, WIDTH, HEIGHT, wingVertices);
                GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                long submissionTime = System.nanoTime() - drawStart;
                long drawTime = GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT);
                if (frame >= 0) { cpu[frame] = elapsed; submit[frame] = submissionTime; gpu[frame] = drawTime; }
            }
        } finally { GL15.glDeleteQueries(query); }
        Arrays.sort(cpu);
        Arrays.sort(submit);
        Arrays.sort(gpu);
        System.out.printf("Cosmetics maximum load: %d vertices, CPU mesh median %.3f ms, CPU submission median %.3f ms, GPU median %.3f ms, GPU p95 %.3f ms (%s)%n",
                mesh.size(), cpu[40] / 1e6, submit[40] / 1e6, gpu[40] / 1e6, gpu[76] / 1e6, GL11.glGetString(GL11.GL_RENDERER));
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private static Matrix4f perspective() {
        return new Matrix4f().perspective((float) Math.toRadians(55), WIDTH / (float) HEIGHT, 0.05f, 100);
    }

    private static void clear() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        GL11.glClearColor(0.035f, 0.043f, 0.06f, 1);
        GL11.glClearDepth(1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    private static BufferedImage capture(String name) throws Exception {
        ByteBuffer data = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
        GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int i = ((HEIGHT - y - 1) * WIDTH + x) * 4;
            image.setRGB(x, y, (data.get(i) & 255) << 16 | (data.get(i + 1) & 255) << 8 | data.get(i + 2) & 255);
        }
        if (name != null) {
            Path dir = Path.of("build/reports/cosmetics");
            Files.createDirectories(dir);
            ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
        }
        return image;
    }

    private static int coloredPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int rgb = image.getRGB(x, y);
            if (((rgb >>> 16) & 255) > 30 || ((rgb >>> 8) & 255) > 30 || (rgb & 255) > 30) count++;
        }
        return count;
    }

    private void avatar() {
        box(-0.25f, 0.72f, -0.13f, 0.25f, 1.42f, 0.13f, 0xFF3C5265);
        box(-0.25f, 1.42f, -0.25f, 0.25f, 1.92f, 0.25f, 0xFF947B72);
        box(-0.25f, 0, -0.12f, -0.02f, 0.72f, 0.12f, 0xFF293541);
        box(0.02f, 0, -0.12f, 0.25f, 0.72f, 0.12f, 0xFF293541);
        box(-0.46f, 0.75f, -0.12f, -0.25f, 1.42f, 0.12f, 0xFF3C5265);
        box(0.25f, 0.75f, -0.12f, 0.46f, 1.42f, 0.12f, 0xFF3C5265);
    }

    private void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        float[][] p = {{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},
                {x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}};
        int[][] faces = {{0,1,2,3},{4,5,6,7},{0,4,7,3},{1,5,6,2},{3,2,6,7},{0,1,5,4}};
        for (int[] face : faces) for (int i : new int[]{0,1,2,0,2,3}) {
            float[] v = p[face[i]];
            mesh.vertex(null, v[0], v[1], v[2], 0, 0, color, 1, 1, 0, 0);
        }
    }
}
