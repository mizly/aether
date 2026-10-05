package dev.aether.ui.orbit;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.renderer.McIcon;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Clipboard;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.GuiClock;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.orbit.panel.PanelHost;
import dev.aether.ui.orbit.panel.PanelView;
import dev.aether.ui.theme.Theme;
import dev.aether.util.AetherResources;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// renders the orbit composition offscreen: real panel ui on the real ring layout, over a stand-in sky, field and
// player, to build/reports/gui-preview/orbit/<scenario>.png
@Tag("gui-preview")
@EnabledIfEnvironmentVariable(named = "AETHER_GUI_PREVIEW", matches = "1")
class OrbitPreviewTest {
    private static final int W = 1600;
    private static final int H = 900;
    private static long window;
    private static GLFWErrorCallback errors;
    private static int fbo;
    private static int color;
    private static int depth;
    private static int program;
    private static int vao;
    private static int vbo;
    private static int white;

    private long now = 1_000_000_000L;
    private final FailsafeRing ring = new FailsafeRing();
    private final List<OrbitWorldRenderer.Quad> injected = new ArrayList<>();

    private static int activeOf(OrbitLayout.Result layout) {
        for (OrbitLayout.Placement p : layout.placements()) if (p.active()) return p.index();
        return 0;
    }

    @BeforeAll
    static void createContext() {
        TestConfigDir.ensure();
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
        window = GLFW.glfwCreateWindow(64, 64, "Orbit preview", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
        NanoVGManager.init();
        GuiCanvas.installItemPainter((nvg, id, x, y, size, tint) -> {
            float drawn = nvg.mcIconSnap(size);
            float offset = (size - drawn) / 2f;
            nvg.mcIcon(new McIcon.Item(id), x + offset, y + offset, size, tint);
        });

        color = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, W, H, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        depth = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, W, H);
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depth);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));

        program = link("orbit_panel.vsh", "orbit_panel.fsh");
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12L);
        white = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, white);
        ByteBuffer px = ByteBuffer.allocateDirect(4).put((byte) -1).put((byte) -1).put((byte) -1).put((byte) -1).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        MainGUIRegistry.invalidate();
        MainGUIRegistry.refresh();
    }

    @AfterAll
    static void destroyContext() {
        NanoVGManager.destroy();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null);
        if (errors != null) errors.free();
    }

    private record Scenario(String name, String category, String page, float zoom, String anchor) {
        Scenario(String name, String category, String page, float zoom) {
            this(name, category, page, zoom, null);
        }
    }

    @Test
    void rendersOrbitScenarios() throws Exception {
        Theme.resetColorsToDefaults();
        PanelView view = new PanelView(new PreviewHost(), () -> now);
        List<String> ids = view.orbitCategoryIds();
        List<Scenario> scenarios = List.of(
                new Scenario("overview", "farming", null, 1f),
                new Scenario("pests", "pests", null, 0f),
                new Scenario("farming", "farming", null, 0f),
                new Scenario("pest-manager", "pests", "pest-manager", 0f),
                new Scenario("safety", "safety", null, 0f),
                new Scenario("farming-macro", "farming", "farming-macro", 0f, "Farm Macro Settings"),
                new Scenario("humanization", "safety", "humanization", 0f));
        String only = System.getProperty("preview.scenario", "");
        List<String> written = new ArrayList<>();
        PanelSurface[] surfaces = new PanelSurface[ids.size()];
        for (int i = 0; i < surfaces.length; i++) surfaces[i] = new PanelSurface();
        for (Scenario scenario : scenarios) {
            if (!only.isEmpty() && !scenario.name().contains(only)) continue;
            int active = ids.indexOf(scenario.category());
            view.orbitFocus(scenario.category());
            if (scenario.anchor() != null) view.orbitOpenPage(scenario.page(), scenario.anchor());
            else if (scenario.page() != null) view.orbitOpenPage(scenario.page());
            for (int f = 0; f < 40; f++) now += 16_666_667L;
            float expand = scenario.page() == null ? 0f : 1f;
            double[][] lean = OrbitRig.lean(scenario.category());
            float lk = (1 - scenario.zoom()) * (expand > 0 ? 0.55f : 1f);
            double[] lp = {lean[0][0] * lk, lean[0][1] * lk, lean[0][2] * lk};
            double[] ll = {lean[1][0] * lk, lean[1][1] * lk, lean[1][2] * lk};
            float[] unfold = new float[ids.size()];
            java.util.Arrays.fill(unfold, 1f);
            OrbitLayout.Result layout = OrbitLayout.compute(new OrbitLayout.Input(new Vector3d(), 0f, 0f, 1.62, 70f,
                    ids.size(), active, scenario.zoom(), expand, 1f, true, 0f, unfold, active, lp, ll, H));
            BufferedImage image = null;
            for (int frame = 0; frame < 45; frame++) {
                now += 16_666_667L;
                image = render(view, ids, surfaces, layout, scenario.zoom(), frame == 44);
            }
            Path out = Path.of("build/reports/gui-preview/orbit/" + scenario.name() + ".png");
            Files.createDirectories(out.getParent());
            ImageIO.write(image, "png", out.toFile());
            written.add(out.toString());
            if (scenario.page() != null) view.orbitBack();
        }
        if (only.isEmpty() || "plots".contains(only)) written.add(renderPlots(view, ids, surfaces));
        if (only.isEmpty() || "travel".contains(only)) written.addAll(renderTravel(view, ids, surfaces));
        if (only.isEmpty() || "hover".contains(only)) written.addAll(renderHovers(view, ids, surfaces));
        if (only.isEmpty() || "search".contains(only)) written.add(renderSearch(view, ids, surfaces));
        written.addAll(renderStages(only));
        System.out.println("orbit previews: " + written);
    }

    // the plot screen fully open over the pests panel, with a garden like the user's real menu
    private String renderPlots(PanelView view, List<String> ids, PanelSurface[] surfaces) throws Exception {
        java.util.Map<Integer, dev.aether.ui.gui.plot.PlotMenuSnapshot.Slot> slots = new java.util.HashMap<>();
        String[] items = {"minecraft:dark_oak_planks", "minecraft:white_stained_glass", "minecraft:nether_wart",
                "minecraft:nether_wart", "minecraft:wheat", "minecraft:lime_stained_glass_pane",
                "minecraft:lime_stained_glass_pane", "minecraft:lime_stained_glass_pane", "minecraft:nether_wart",
                "minecraft:white_stained_glass", "minecraft:orange_stained_glass_pane", "minecraft:cocoa_beans",
                "minecraft:cocoa_beans", "minecraft:lime_stained_glass_pane", "minecraft:orange_stained_glass_pane",
                "minecraft:lime_stained_glass_pane", "minecraft:lime_stained_glass_pane", "minecraft:lime_stained_glass_pane",
                "minecraft:lime_stained_glass_pane", "minecraft:lime_stained_glass_pane", "minecraft:lime_stained_glass_pane",
                "minecraft:cocoa_beans", "minecraft:orange_stained_glass_pane", "minecraft:cocoa_beans",
                "minecraft:cocoa_beans"};
        for (int plot = 0; plot <= 24; plot++) {
            slots.put(plot, new dev.aether.ui.gui.plot.PlotMenuSnapshot.Slot(items[plot],
                    plot == 0 ? "The Barn" : "Plot - " + plot, List.of()));
        }
        var snapshot = new dev.aether.ui.gui.plot.PlotMenuSnapshot(slots, 0L);
        dev.aether.ui.gui.plot.GardenPlotData.install(new dev.aether.ui.gui.plot.GardenPlotData() {
            @Override public int currentPlot() { return 5; }
            @Override public java.util.Set<Integer> infestedPlots() { return java.util.Set.of(12, 21); }
            @Override public int pestCount() { return 3; }
            @Override public dev.aether.ui.gui.plot.PlotMenuSnapshot snapshot() { return snapshot; }
        });
        List<String> values = new ArrayList<>(List.of("3", "5", "12"));
        var setting = new dev.aether.ui.settings.PlotSetting("Leave One Pest Plots",
                dev.aether.ui.settings.PlotSetting.Mode.MULTI, () -> values, v -> { values.clear(); values.addAll(v); });
        int active = ids.indexOf("pests");
        view.orbitFocus("pests");
        double[][] lean = OrbitRig.lean("pests");
        float[] unfold = new float[ids.size()];
        java.util.Arrays.fill(unfold, 1f);
        OrbitLayout.Result layout = OrbitLayout.compute(new OrbitLayout.Input(new Vector3d(), 0f, 0f, 1.62, 70f,
                ids.size(), active, 0f, 0f, 1f, true, 0f, unfold, active, lean[0], lean[1], H));
        OrbitPlotScreen screen = new OrbitPlotScreen(setting, new float[]{W * 0.6f, H * 0.4f, 60f, 60f}, 30f);
        BufferedImage image = null;
        for (int frame = 0; frame < 70; frame++) {
            now += 16_666_667L;
            render(view, ids, surfaces, layout, 0f, false);
            float gw = W / 2f, gh = H / 2f;
            NanoVGManager.beginFrame(gw, gh, 2f);
            try {
                screen.render(NanoVGManager.getRenderer(), gw, gh, gw * 0.62f, gh * 0.42f, 1f / 60f, frame / 60f);
            } finally {
                NanoVGManager.endFrame();
            }
            if (frame == 69) image = read();
        }
        dev.aether.ui.gui.plot.GardenPlotData.install(null);
        Path out = Path.of("build/reports/gui-preview/orbit/plots.png");
        ImageIO.write(image, "png", out.toFile());
        return out.toString();
    }

    // each skit of the farm's actors played from the start, nine frames of it in a filmstrip
    private record Stage(String name, SceneActors.Inputs inputs, float[] times, Vector3f eye, Vector3f at) {
    }

    private List<String> renderStages(String only) throws Exception {
        Vector3f front = new Vector3f(3.2f, 2.4f, 6.5f), side = new Vector3f(9f, 3.4f, 4.5f);
        Vector3f centre = new Vector3f(0.4f, 0.9f, 0f);
        Vector3f game = new Vector3f((float) OrbitRig.TP_POS.x, (float) OrbitRig.TP_POS.y + 1.62f, (float) OrbitRig.TP_POS.z);
        Vector3f gameAt = new Vector3f((float) OrbitRig.TP_LOOK.x, (float) OrbitRig.TP_LOOK.y, (float) OrbitRig.TP_LOOK.z);
        float[] pestTimes = {0.6f, 1.0f, 1.4f, 2.2f, 3.2f, 4.6f, 5.6f, 6.6f, 8.6f};
        List<Stage> stages = List.of(
                new Stage("stage-pests", new SceneActors.Inputs("Pest Manager", true, 0, 5, 0), pestTimes,
                        new Vector3f(-4.5f, 3.6f, -5.5f), new Vector3f(1.4f, 0.9f, 0.6f)),
                new Stage("stage-pests-game", new SceneActors.Inputs("Pest Manager", true, 0, 5, 0), pestTimes, game, gameAt),
                new Stage("stage-bed", new SceneActors.Inputs("Dynamic Rest", true, 0, 0, 0),
                        new float[]{0.45f, 0.85f, 1.4f, 1.8f, 2.2f, 2.8f, 3.25f, 3.6f, 5.5f}, new Vector3f(-2.5f, 3.2f, 4.5f),
                        new Vector3f(1.8f, 0.6f, -0.4f)),
                new Stage("stage-bed-game", new SceneActors.Inputs("Dynamic Rest", true, 0, 0, 0),
                        new float[]{0.45f, 0.85f, 1.4f, 1.8f, 2.2f, 2.8f, 3.25f, 3.6f, 5.5f}, game, gameAt),
                new Stage("stage-craft", new SceneActors.Inputs("Auto Supercraft", true, 0, 0, 0),
                        new float[]{0.2f, 0.45f, 0.8f, 1.0f, 1.3f, 2.4f, 2.7f, 6f, 12f}, new Vector3f(4.5f, 2.6f, 4.5f),
                        new Vector3f(0.5f, 0.9f, 0.7f)),
                new Stage("stage-craft-off", new SceneActors.Inputs("Auto Supercraft", false, 0, 0, 0),
                        new float[]{0.6f, 1f, 1.4f, 1.8f, 2.2f, 2.6f, 3f, 3.4f, 3.8f}, new Vector3f(4.5f, 2.6f, 4.5f),
                        new Vector3f(0.5f, 0.9f, 0.7f)),
                new Stage("stage-visitors", new SceneActors.Inputs("Auto Visitor", true, 4, 0, 0),
                        new float[]{1f, 2.5f, 4f, 5.5f, 7f, 8.5f, 10f, 11.5f, 13f}, new Vector3f(9f, 3.4f, 4.5f),
                        new Vector3f(0f, 0.9f, 4.5f)),
                new Stage("stage-gold", new SceneActors.Inputs("Auto Visitor", true, 1, 0, 20),
                        new float[]{0.3f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f}, new Vector3f(3.2f, 2.4f, 6.5f),
                        new Vector3f(0.4f, 0.9f, 0f)),
                new Stage("stage-loadout", new SceneActors.Inputs("Auto Loadout", true, 0, 0, 0),
                        new float[]{0.5f, 1.3f, 1.8f, 2.2f, 3.0f, 3.9f, 4.5f, 6.4f, 7.2f}, new Vector3f(1.5f, 2.6f, 9.5f),
                        new Vector3f(3f, 0.9f, 1.5f)),
                new Stage("stage-loadout-game", new SceneActors.Inputs("Auto Loadout", true, 0, 0, 0),
                        new float[]{0.5f, 1.3f, 1.8f, 2.2f, 3.0f, 3.9f, 4.5f, 6.4f, 7.2f}, game, gameAt));
        int scene = link("orbit_scene.vsh", "orbit_scene.fsh");
        int stageVao = GL30.glGenVertexArrays();
        int stageVbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(stageVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, stageVbo);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, SceneClone.STRIDE, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, SceneClone.STRIDE, 12L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, SceneClone.STRIDE, 20L);
        java.util.Map<String, Integer> textures = new java.util.HashMap<>();
        Path headDir = Path.of(System.getProperty("preview.heads", "/tmp/claude-1000/heads"));
        List<Path> heads = new ArrayList<>();
        if (java.nio.file.Files.isDirectory(headDir)) {
            try (var files = java.nio.file.Files.list(headDir)) {
                files.filter(f -> !f.getFileName().toString().startsWith("face_")).sorted().forEach(heads::add);
            }
        }
        java.util.function.ToIntFunction<net.minecraft.resources.Identifier> texture = id -> textures.computeIfAbsent(id.toString(), k -> {
            try {
                if (id.getNamespace().equals("preview")) {
                    return textureFrom(java.nio.file.Files.newInputStream(heads.get(Integer.parseInt(id.getPath()))));
                }
                return textureFrom(OrbitPreviewTest.class.getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath()));
            } catch (Exception e) {
                throw new IllegalStateException(id.toString(), e);
            }
        });
        int skin = textureFrom(OrbitPreviewTest.class.getResourceAsStream("/assets/minecraft/textures/entity/player/wide/steve.png"));
        List<String> out = new ArrayList<>();
        for (Stage stage : stages) {
            if (!only.isEmpty() && !stage.name().contains(only)) continue;
            SceneActors actors = new SceneActors(i -> heads.isEmpty() ? null
                    : net.minecraft.resources.Identifier.fromNamespaceAndPath("preview", Integer.toString(i % heads.size())));
            PlayerFigure figure = new PlayerFigure();
            Vector3d eye = new Vector3d(stage.eye());
            Vector3d look = new Vector3d(stage.at());
            Vector3d forward = new Vector3d(look).sub(eye).normalize();
            Vector3d right = new Vector3d(forward).cross(0, 1, 0).normalize();
            Vector3d up = new Vector3d(right).cross(forward).normalize();
            float fov = stage.name().endsWith("-game") ? OrbitRig.FOV : 40f;
            OrbitLayout.Camera cam = new OrbitLayout.Camera(eye, look, forward, right, up, fov);
            Matrix4f vp = new Matrix4f().perspective((float) Math.toRadians(fov), (float) W / H, 0.05f, 200f)
                    .lookAt(stage.eye(), stage.at(), new Vector3f(0, 1, 0));
            BufferedImage strip = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = strip.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            float t = 0f, dt = 1f / 60f;
            int shot = 0;
            SceneClone.Buffer body = new SceneClone.Buffer(512);
            float[] prevV = null;
            float prevD = 0f;
            java.util.List<float[]> spikes = new java.util.ArrayList<>();
            while (shot < stage.times().length) {
                t += dt;
                actors.update(dt, t, stage.inputs(), figure);
                figure.lookAt((float) eye.x, (float) eye.y, (float) eye.z, dt);
                body.reset();
                figure.build(body, new Matrix4f(), false, t);
                var draws = actors.build(new Matrix4f(), new Vector3d(eye), new Vector3f(cam.right()), new Vector3f(cam.up()), figure);
                // PREVIEW_MOTION=1 prints the frames where the figure's motion jumps hardest, to hunt snaps
                if (System.getenv("PREVIEW_MOTION") != null) {
                    int n = Math.min(body.count, 432);
                    java.nio.ByteBuffer fin = body.finish();
                    java.nio.ByteBuffer bb = fin.duplicate().order(java.nio.ByteOrder.nativeOrder());
                    float[] vs = new float[n * 3];
                    for (int i = 0; i < n; i++) {
                        vs[i * 3] = bb.getFloat(i * SceneClone.STRIDE);
                        vs[i * 3 + 1] = bb.getFloat(i * SceneClone.STRIDE + 4);
                        vs[i * 3 + 2] = bb.getFloat(i * SceneClone.STRIDE + 8);
                    }
                    fin.position(fin.limit());
                    fin.limit(fin.capacity());
                    if (prevV != null) {
                        float d = 0f;
                        for (int i = 0; i < Math.min(vs.length, prevV.length); i++) d = Math.max(d, Math.abs(vs[i] - prevV[i]));
                        spikes.add(new float[]{t, d, Math.abs(d - prevD)});
                        prevD = d;
                    }
                    prevV = vs;
                }
                if (t + 1e-4f < stage.times()[shot]) continue;
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                GL11.glViewport(0, 0, W, H);
                GL11.glClearColor(0.6f, 0.76f, 0.95f, 1f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                GL11.glDisable(GL11.GL_BLEND);
                GL20.glUseProgram(program);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ViewProjection"), false, vp.get(stack.mallocFloat(16)));
                }
                GL20.glUniform1i(GL20.glGetUniformLocation(program, "Panel"), 0);
                GL30.glBindVertexArray(vao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                stageGround();
                GL20.glUseProgram(scene);
                GL20.glUniform1i(GL20.glGetUniformLocation(scene, "Sampler"), 0);
                GL20.glUniform3f(GL20.glGetUniformLocation(scene, "Offset"), 0f, 0f, 0f);
                GL20.glUniform2f(GL20.glGetUniformLocation(scene, "FogRange"), 1e8f, 2e8f);
                GL20.glUniform1f(GL20.glGetUniformLocation(scene, "AlphaCut"), 0.1f);
                GL20.glUniform1f(GL20.glGetUniformLocation(scene, "Solid"), 1f);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(scene, "ViewProjection"), false, vp.get(stack.mallocFloat(16)));
                }
                GL30.glBindVertexArray(stageVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, stageVbo);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, skin);
                int bodyCount = body.count;
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, body.finish(), GL15.GL_STREAM_DRAW);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, bodyCount);
                for (SceneActors.Draw d : draws) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.applyAsInt(d.texture()));
                    int count = d.buffer().count;
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, d.buffer().finish(), GL15.GL_STREAM_DRAW);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
                }
                int col = shot % 3, row = shot / 3;
                g.drawImage(read(), col * W / 3, row * H / 3, W / 3, H / 3, null);
                g.setColor(java.awt.Color.WHITE);
                g.drawString(String.format("t=%.2f", t), col * W / 3 + 6, row * H / 3 + 16);
                shot++;
            }
            g.dispose();
            if (!spikes.isEmpty()) {
                spikes.sort((a, b) -> Float.compare(b[2], a[2]));
                StringBuilder sb = new StringBuilder(stage.name() + " worst jerks:");
                for (int i = 0; i < Math.min(6, spikes.size()); i++) sb.append(String.format(" t=%.2f d=%.3f j=%.3f", spikes.get(i)[0], spikes.get(i)[1], spikes.get(i)[2]));
                System.out.println(sb);
            }
            body.free();
            actors.close();
            Path file = Path.of("build/reports/gui-preview/orbit/" + stage.name() + ".png");
            ImageIO.write(strip, "png", file.toFile());
            out.add(file.toString());
        }
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        return out;
    }

    // a flat sketch of the preset farm around the player: the path and its cross path, wheat and potato fields and
    // the hedged yard beside the path, laid out like PresetGarden with the player in the middle of block 0
    private void stageGround() {
        flat(-40, 40, -40, 40, 0f, 0.38f, 0.58f, 0.25f);
        flat(2.5, 34.5, -8.5, 13.5, 0.004, 0.78f, 0.68f, 0.3f);
        flat(-34.5, -2.5, -8.5, 13.5, 0.004, 0.42f, 0.55f, 0.22f);
        for (int lane = -34; lane <= 34; lane++) {
            if (Math.abs(lane) < 3 || Math.floorMod(Math.abs(lane) - 3, 9) != 4) continue;
            flat(lane - 0.5, lane + 0.5, -8.5, 13.5, 0.006, 0.25f, 0.42f, 0.85f);
        }
        flat(1.5, 8.5, -5.5, 7.5, 0.008, 0.38f, 0.58f, 0.25f);
        flat(-1.5, 1.5, -12.5, 38.5, 0.01, 0.58f, 0.45f, 0.3f);
        flat(-38.5, 38.5, 12.5, 15.5, 0.01, 0.58f, 0.45f, 0.3f);
        box(7.5, 0, -5.5, 8.5, 1, 7.5, 0.2f, 0.45f, 0.15f);
        box(1.5, 0, -5.5, 8.5, 1, -4.5, 0.2f, 0.45f, 0.15f);
        box(1.5, 0, 6.5, 8.5, 1, 7.5, 0.2f, 0.45f, 0.15f);
        for (double[] post : new double[][]{{1.5, -5.5}, {7.5, -5.5}, {1.5, 6.5}, {7.5, 6.5}}) {
            box(post[0], 0, post[1], post[0] + 1, 2.2, post[1] + 1, 0.55f, 0.42f, 0.25f);
        }
    }

    private void flat(double x0, double x1, double z0, double z1, double y, float r, float g, float b) {
        quad(new Vector3d(x0, y, z1), new Vector3d(x1, y, z1), new Vector3d(x1, y, z0), new Vector3d(x0, y, z0), white, 1f, 0f, r, g, b);
    }

    private static int textureFrom(java.io.InputStream in) throws Exception {
        BufferedImage img;
        try (in) {
            img = ImageIO.read(in);
        }
        return upload(img);
    }

    private static int upload(BufferedImage img) {
        ByteBuffer px = ByteBuffer.allocateDirect(img.getWidth() * img.getHeight() * 4);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                px.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        px.flip();
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, img.getWidth(), img.getHeight(), 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, px);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return tex;
    }

    // the top bar search open on a query, over the pests panel
    private String renderSearch(PanelView view, List<String> ids, PanelSurface[] surfaces) throws Exception {
        int active = ids.indexOf("pests");
        view.orbitFocus("pests");
        double[][] lean = OrbitRig.lean("pests");
        float[] unfold = new float[ids.size()];
        java.util.Arrays.fill(unfold, 1f);
        OrbitLayout.Result layout = OrbitLayout.compute(new OrbitLayout.Input(new Vector3d(), 0f, 0f, 1.62, 70f,
                ids.size(), active, 0f, 0f, 1f, true, 0f, unfold, active, lean[0], lean[1], H));
        OrbitSearchBar bar = new OrbitSearchBar(view, () -> { });
        bar.type("pest th");
        BufferedImage image = null;
        for (int frame = 0; frame < 30; frame++) {
            now += 16_666_667L;
            render(view, ids, surfaces, layout, 0f, false);
            float gw = W / 2f, gh = H / 2f;
            NanoVGManager.beginFrame(gw, gh, 2f);
            try {
                bar.render(NanoVGManager.getRenderer(), dev.aether.ui.gui.Palette.fromTheme(), gw / 2f - 110f, 10f, 220f,
                        gw, gh, gw / 2f, 62f, 1f);
            } finally {
                NanoVGManager.endFrame();
            }
            if (frame == 29) image = read();
        }
        Path out = Path.of("build/reports/gui-preview/orbit/search.png");
        ImageIO.write(image, "png", out.toFile());
        return out.toString();
    }

    // each in-world setting preview, as if the cursor rested on its row
    private List<String> renderHovers(PanelView view, List<String> ids, PanelSurface[] surfaces) throws Exception {
        var radius = new dev.aether.ui.settings.SliderSetting("Etherwarp Minimum Distance (Blocks)", 10, 50, () -> 22f, v -> { });
        var threshold = new dev.aether.ui.settings.SliderSetting("Pest Threshold", 1, 8, () -> 3f, v -> { });
        var start = new dev.aether.ui.settings.PositionSetting("Rewarp Start", () -> 0.0, v -> { }, () -> 0.0, v -> { },
                () -> 0.0, v -> { }, () -> false, v -> { }, () -> { });
        var pitch = new dev.aether.ui.settings.SliderSetting("Pitch", -90, 90, () -> 3f, v -> { });
        var pair = dev.aether.config.RewarpPointPair.defaultPair(0);
        pair.name = "Wheat";
        pair.startX = -12.5;
        pair.startZ = 30.5;
        pair.startSet = true;
        pair.endX = 14.5;
        pair.endZ = 44.5;
        var garden = new dev.aether.ui.gui.plot.GardenFacts(5, java.util.Map.of(4, 3, 8, 1, 12, 2), null);
        record Hover(String name, String category, PanelView.Hover hover) {
        }
        List<Hover> hovers = List.of(
                new Hover("hover-radius", "farming", new PanelView.Hover(radius, "farming-macro", "Etherwarp")),
                new Hover("hover-pests", "pests", new PanelView.Hover(threshold, "pest-manager", "Pest Destroyer")),
                new Hover("hover-rewarp", "farming", new PanelView.Hover(start, "rewarp", "Rewarp 1")),
                new Hover("hover-cone", "farming", new PanelView.Hover(pitch, "farming-macro", "Farm Macro Settings")));
        List<String> out = new ArrayList<>();
        for (Hover h : hovers) {
            int active = ids.indexOf(h.category());
            view.orbitFocus(h.category());
            double[][] lean = OrbitRig.lean(h.category());
            float[] unfold = new float[ids.size()];
            java.util.Arrays.fill(unfold, 1f);
            OrbitLayout.Result layout = OrbitLayout.compute(new OrbitLayout.Input(new Vector3d(), 0f, 0f, 1.62, 70f,
                    ids.size(), active, 0f, 0f, 1f, true, 0f, unfold, active, lean[0], lean[1], H));
            SettingPreview preview = new SettingPreview();
            var world = new SettingPreview.World(new Vector3d(), 1.62, 0f, 3f, garden, List.of(pair));
            BufferedImage image = null;
            for (int frame = 0; frame < 40; frame++) {
                now += 16_666_667L;
                preview.step(1f / 60f, h.hover(), true);
                injected.clear();
                preview.appendQuads(injected, world, layout.camera(), frame / 60f);
                image = render(view, ids, surfaces, layout, 0f, frame == 39);
            }
            injected.clear();
            preview.close();
            Path file = Path.of("build/reports/gui-preview/orbit/" + h.name() + ".png");
            ImageIO.write(image, "png", file.toFile());
            out.add(file.toString());
        }
        return out;
    }

    // frames of the island travel film both ways, over the overview it opens into
    private List<String> renderTravel(PanelView view, List<String> ids, PanelSurface[] surfaces) throws Exception {
        int active = ids.indexOf("farming");
        float[] unfold = new float[ids.size()];
        java.util.Arrays.fill(unfold, 1f);
        OrbitLayout.Result layout = OrbitLayout.compute(new OrbitLayout.Input(new Vector3d(), 0f, 0f, 1.62, 70f,
                ids.size(), active, 1f, 0f, 1f, true, 0f, unfold, active, new double[3], new double[3], H));
        TravelCinematic.FacePainter face = (nvg, x, y, size, alpha) -> {
            nvg.rect(x, y, size, size, 0xFF6B4F3A);
            nvg.rect(x, y, size, size * 0.25f, 0xFF3A2A1E);
            nvg.rect(x + size * 0.125f, y + size * 0.5f, size * 0.25f, size * 0.125f, 0xFFFFFFFF);
            nvg.rect(x + size * 0.625f, y + size * 0.5f, size * 0.25f, size * 0.125f, 0xFFFFFFFF);
        };
        List<String> out = new ArrayList<>();
        float[] times = {0.7f, 1.22f, 1.3f, 1.9f, 2.45f};
        OrbitIsland[][] trips = {{OrbitIsland.GARDEN, OrbitIsland.CRIMSON_ISLE}, {OrbitIsland.CRIMSON_ISLE, OrbitIsland.GARDEN}};
        for (OrbitIsland[] trip : trips) {
            TravelCinematic film = new TravelCinematic(trip[0], trip[1], face);
            for (int i = 0; i < times.length; i++) {
                render(view, ids, surfaces, layout, 1f, false);
                float gw = W / 2f, gh = H / 2f;
                NanoVGManager.beginFrame(gw, gh, 2f);
                try {
                    film.render(NanoVGManager.getRenderer(), gw, gh, times[i]);
                } finally {
                    NanoVGManager.endFrame();
                }
                Path file = Path.of("build/reports/gui-preview/orbit/travel-" + trip[1].name().toLowerCase() + "-" + i + ".png");
                ImageIO.write(read(), "png", file.toFile());
                out.add(file.toString());
            }
        }
        return out;
    }

    private BufferedImage render(PanelView view, List<String> ids, PanelSurface[] surfaces, OrbitLayout.Result layout,
                                 float zoom, boolean capture) {
        float k = (float) OrbitLayout.pixelRatio(H);
        for (OrbitLayout.Placement p : layout.placements()) {
            String id = ids.get(p.index());
            if (p.active() && zoom < 0.5f) {
                surfaces[p.index()].render(p.designW(), p.designH(), k,
                        nvg -> view.renderOrbitActive(nvg, p.designW(), p.designH(), -1f, -1f, id));
            } else {
                surfaces[p.index()].render(p.designW(), p.designH(), k * 0.6f,
                        nvg -> view.renderOrbitPassive(nvg, p.designW(), p.designH(), id, zoom));
            }
        }
        OrbitLayout.Camera cam = layout.camera();
        List<OrbitWorldRenderer.Quad> extra = new ArrayList<>(injected);
        if (zoom < 0.5f && "safety".equals(ids.get(activeOf(layout)))) {
            for (int i = 0; i < 40; i++) ring.step(1f / 60f, true);
            ring.appendQuads(extra, view.orbitFailsafes(), "Rotation", new Vector3d(), cam, 1f);
        }
        Matrix4f vp = new Matrix4f().perspective((float) Math.toRadians(cam.fov()), (float) W / H, 0.05f, 600f)
                .lookAt((float) cam.pos().x, (float) cam.pos().y, (float) cam.pos().z,
                        (float) cam.look().x, (float) cam.look().y, (float) cam.look().z, 0f, 1f, 0f);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, W, H);
        GL11.glClearColor(0.56f, 0.72f, 0.92f, 1f);
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL20.glUseProgram(program);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ViewProjection"), false, vp.get(stack.mallocFloat(16)));
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "Panel"), 0);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthMask(true);
        scene();
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        for (OrbitWorldRenderer.Quad q : extra) {
            quad(q.topLeft(), q.topRight(), q.bottomRight(), q.bottomLeft(), q.texture(), q.alpha(), q.dim(), 1f, 1f, 1f);
        }
        List<OrbitLayout.Placement> order = new ArrayList<>(List.of(layout.placements()));
        order.sort(Comparator.comparingDouble(p -> -p.center().distanceSquared(cam.pos())));
        for (OrbitLayout.Placement p : order) {
            PanelSurface surface = surfaces[p.index()];
            float radius = PanelView.ORBIT_RADIUS;
            quad(p.corner(-1, 1), p.corner(1, 1), p.corner(1, -1), p.corner(-1, -1), surface.texture(),
                    p.alpha(), p.dim(), 1f, 1f, 1f, surface.uMax(), surface.vMax(), radius / p.designW(), radius / p.designH());
        }
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        return capture ? read() : null;
    }

    // a crude field and a blocky figure so the panels have something to sit around
    private void scene() {
        quad(new Vector3d(-80, 0, 120), new Vector3d(80, 0, 120), new Vector3d(80, 0, -60), new Vector3d(-80, 0, -60),
                white, 1f, 0f, 0.38f, 0.55f, 0.24f);
        for (int lane = -6; lane <= 6; lane++) {
            double x = lane * 4.0;
            quad(new Vector3d(x - 0.5, 0.01, 120), new Vector3d(x + 0.5, 0.01, 120), new Vector3d(x + 0.5, 0.01, -60),
                    new Vector3d(x - 0.5, 0.01, -60), white, 1f, 0f, 0.29f, 0.42f, 0.62f);
        }
        box(-0.25, 0, -0.12, 0.25, 0.75, 0.12, 0.25f, 0.27f, 0.55f);
        box(-0.25, 0.75, -0.12, 0.25, 1.5, 0.12, 0.16f, 0.62f, 0.66f);
        box(-0.25, 1.5, -0.25, 0.25, 2.0, 0.25, 0.62f, 0.45f, 0.33f);
    }

    private void box(double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b) {
        quad(new Vector3d(x0, y1, z0), new Vector3d(x1, y1, z0), new Vector3d(x1, y0, z0), new Vector3d(x0, y0, z0), white, 1, 0, r * 0.8f, g * 0.8f, b * 0.8f);
        quad(new Vector3d(x1, y1, z1), new Vector3d(x0, y1, z1), new Vector3d(x0, y0, z1), new Vector3d(x1, y0, z1), white, 1, 0, r, g, b);
        quad(new Vector3d(x0, y1, z1), new Vector3d(x0, y1, z0), new Vector3d(x0, y0, z0), new Vector3d(x0, y0, z1), white, 1, 0, r * 0.9f, g * 0.9f, b * 0.9f);
        quad(new Vector3d(x1, y1, z0), new Vector3d(x1, y1, z1), new Vector3d(x1, y0, z1), new Vector3d(x1, y0, z0), white, 1, 0, r * 0.7f, g * 0.7f, b * 0.7f);
        quad(new Vector3d(x0, y1, z0), new Vector3d(x1, y1, z0), new Vector3d(x1, y1, z1), new Vector3d(x0, y1, z1), white, 1, 0, r, g, b);
    }

    private void quad(Vector3d tl, Vector3d tr, Vector3d br, Vector3d bl, int texture, float alpha, float dim,
                      float r, float g, float b) {
        quad(tl, tr, br, bl, texture, alpha, dim, r, g, b, 1f, 1f, 0f, 0f);
    }

    private void quad(Vector3d tl, Vector3d tr, Vector3d br, Vector3d bl, int texture, float alpha, float dim,
                      float r, float g, float b, float uMax, float vMax, float cornerU, float cornerV) {
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "UvMax"), uMax, vMax);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "Corner"), cornerU, cornerV);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer data = stack.mallocFloat(30);
            put(data, tl, 0, 1);
            put(data, bl, 0, 0);
            put(data, br, 1, 0);
            put(data, tl, 0, 1);
            put(data, br, 1, 0);
            put(data, tr, 1, 1);
            data.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "Tint"), r * alpha, g * alpha, b * alpha, alpha);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "Dim"), dim);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
    }

    private static void put(FloatBuffer data, Vector3d p, float u, float v) {
        data.put((float) p.x).put((float) p.y).put((float) p.z).put(u).put(v);
    }

    private static BufferedImage read() {
        ByteBuffer data = ByteBuffer.allocateDirect(W * H * 4);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glReadPixels(0, 0, W, H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = ((H - y - 1) * W + x) * 4;
                image.setRGB(x, y, (data.get(i) & 255) << 16 | (data.get(i + 1) & 255) << 8 | data.get(i + 2) & 255);
            }
        }
        return image;
    }

    private static int link(String vertex, String fragment) {
        int v = compile(GL20.GL_VERTEX_SHADER, vertex);
        int f = compile(GL20.GL_FRAGMENT_SHADER, fragment);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        assertNotEquals(0, GL20.glGetProgrami(p, GL20.GL_LINK_STATUS), GL20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String name) {
        int shader = GL20.glCreateShader(type);
        try (var stream = AetherResources.open("/assets/aether/shaders/" + name)) {
            GL20.glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        GL20.glCompileShader(shader);
        assertNotEquals(0, GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS), GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static final class PreviewHost implements PanelHost {
        private String clipboard = "";

        @Override public Account account() { return new Account("Valen", AuthState.SIGNED_IN, 312L * 3600L); }
        @Override public boolean streamerMode() { return false; }
        @Override public Session session() { return new Session("Farming Macro", "diamond_hoe", true, 72L * 60_000L, 23L * 60_000L, null, 0L); }
        @Override public List<String> profiles() { return List.of("main"); }
        @Override public String activeProfile() { return "main"; }
        @Override public void loadProfile(String name) { }
        @Override public void beginLogin() { }
        @Override public void resume() { }
        @Override public void openMacroMenu() { }
        @Override public void openHudEditor() { }
        @Override public void close() { }

        @Override
        public void paintHead(GuiCanvas canvas, float x, float y, float size) {
            canvas.roundedRect(new dev.aether.ui.gui.Rect(x, y, size, size), 3f, Argb.withAlpha(0xFF6B4F3A, 1f));
        }

        @Override
        public Clipboard clipboard() {
            return new Clipboard() {
                @Override public String read() { return clipboard; }
                @Override public void write(String text) { clipboard = text; }
            };
        }
    }
}
