package dev.aether.hud;

import dev.aether.modules.profit.SessionProfitHistory;
import dev.aether.renderer.NanoVGManager;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.nanovg.NanoVG.*;

@EnabledIfEnvironmentVariable(named = "AETHER_TEST_OPENGL", matches = "1")
class ProfitGraphRenderTest {
    private static final int WIDTH = 990;
    private static final int HEIGHT = 660;
    private static long window;
    private static GLFWErrorCallback errors;

    @BeforeAll
    static void createContext() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-graph-test"));
        errors = GLFWErrorCallback.createPrint(System.err);
        GLFW.glfwSetErrorCallback(errors);
        if (System.getenv("DISPLAY") != null && System.getProperty("os.name").equals("Linux"))
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        assertTrue(GLFW.glfwInit());
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "Aether profit graph", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        NanoVGManager.init();
    }

    @AfterAll
    static void destroyContext() {
        NanoVGManager.destroy();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null);
        errors.free();
    }

    @Test
    void rendersScaledGraphsWithCostsEmptyHistoryAndLargeBalances() throws Exception {
        GL11.glViewport(0, 0, WIDTH, HEIGHT);
        GL11.glClearColor(0.025f, 0.03f, 0.04f, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        long vg = NanoVGManager.getVg();
        nvgBeginFrame(vg, WIDTH, HEIGHT, 1);
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
        var nvg = NanoVGManager.getRenderer();
        nvg.scale(1.5f, 1.5f);
        for (int scenario = 0; scenario < 4; scenario++) {
            var history = new SessionProfitHistory();
            history.reset(0, true);
            long profit = scenario == 3 ? 1_234_567_000_000L : 0;
            for (int second = 1; second <= 600; second++) {
                profit += switch (scenario) {
                    case 0 -> second % 30 == 0 ? -15_000 : 1_100;
                    case 1 -> second % 60 == 0 ? 30_000 : -600;
                    case 3 -> second % 50 == 0 ? 1 : 0;
                    default -> 0;
                };
                history.record(second * 1_000_000_000L, profit);
            }
            nvg.save();
            nvg.translate(15 + scenario % 2 * 325, 15 + scenario / 2 * 215);
            HudStyle.panel(nvg, 300, 192);
            String title = switch (scenario) {
                case 0 -> "Session Profit";
                case 1 -> "Costs and earnings";
                case 2 -> "No tracked drops yet";
                default -> "Large balance";
            };
            HudStyle.header(nvg, 300, title, "COINS");
            new ProfitGraph().render(nvg, 12, 42, 276, history.snapshot(600_000_000_000L), 300_000, 0);
            nvg.restore();
        }
        nvgEndFrame(vg);
        var pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            var rendered = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            int graphPixels = 0;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int index = ((HEIGHT - y - 1) * WIDTH + x) * 4;
                    int r = pixels.get(index) & 255, g = pixels.get(index + 1) & 255, b = pixels.get(index + 2) & 255;
                    rendered.setRGB(x, y, r << 16 | g << 8 | b);
                    if (x > 110 && x < 440 && y > 95 && y < 200 && b > r * 1.3 && b > 80) graphPixels++;
                }
            }
            Path output = Path.of("build/reports/tests/profit-graph.png");
            Files.createDirectories(output.getParent());
            ImageIO.write(rendered, "png", output.toFile());
            assertTrue(graphPixels > 100, "The profit trace must be visible in the plot area at HUD scale");
        } finally {
            MemoryUtil.memFree(pixels);
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }
}
