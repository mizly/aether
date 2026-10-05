package dev.aether.ui.orbit;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.aether.Aether;
import dev.aether.macro.MacroCatalog;
import dev.aether.macro.MacroStateManager;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import dev.aether.ui.gui.GuiClock;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.PointerInput;
import dev.aether.ui.orbit.panel.PanelView;
import dev.aether.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// the orbit menu: category panels hang on a ring around the player in the real world and the camera films it.
// everything is client-side rendering; the player entity is never moved, turned or reported anywhere
public final class OrbitScreen extends Screen {
    private enum State { OPENING, OPEN, CLOSING }

    private final PanelView view;
    private final List<String> categories;
    private final int count;
    private final double step;
    private final PanelSurface[] surfaces;
    private final OrbitSpring[] unfold;
    private final OrbitWorldRenderer renderer = new OrbitWorldRenderer();
    private final FailsafeRing failsafeRing = new FailsafeRing();
    private final SettingPreview settingPreview = new SettingPreview();
    private float lastDt;
    private final OrbitOverlay overlay;

    private final OrbitSpring ring = new OrbitSpring(0f, 130f, 15.5f);
    private final OrbitSpring zoom = new OrbitSpring(1f, 60f, 13f);
    private final OrbitSpring expand = new OrbitSpring(0f, 120f, 16f);
    private final OrbitSpring[] leanPos = {new OrbitSpring(0, 30, 11), new OrbitSpring(0, 30, 11), new OrbitSpring(0, 30, 11)};
    private final OrbitSpring[] leanLook = {new OrbitSpring(0, 30, 11), new OrbitSpring(0, 30, 11), new OrbitSpring(0, 30, 11)};

    private State state = State.OPENING;
    private float openT;
    private float closeT;
    private float hold = 0.05f;
    private long lastNanos;
    private float clock;
    private float momentum;
    private boolean draggingRing;
    private double dragLastX;
    private boolean pressedPanel;
    private float wheelLock;

    private float yaw0;
    private float pitch0;
    private OrbitLayout.Result layout;
    private OrbitPlotScreen plotScreen;
    private long plotNanos;
    private double mouseX = -1;
    private double mouseY = -1;
    private TravelCinematic cinematic;
    private final float openSeconds = 1.55f;
    private final float closeSeconds = 0.8f;
    private OrbitIsland island;
    private SceneClone.Mesh clone;
    private final SceneRenderer sceneRenderer = new SceneRenderer();
    private final PlayerFigure figure = new PlayerFigure();
    private SceneClone.Buffer figureBuffer;
    private SceneHotspots hotspots;
    private SceneHotspots.Hotspot pressedHotspot;
    private double pressX, pressY;
    private final OrbitSearchBar searchBar;
    private String focused;

    public OrbitScreen() {
        super(Component.literal("Aether"));
        boolean stopped = MacroStateManager.isAutomationRunning();
        if (stopped) {
            MacroStateManager.stopMacro(Minecraft.getInstance(), "MainGUI opened", false);
        }
        OrbitHost host = new OrbitHost(this, stopped || MacroCatalog.lastStarted().isPresent());
        view = new PanelView(host, GuiClock.SYSTEM);
        categories = view.orbitCategoryIds();
        count = Math.max(1, categories.size());
        step = Math.PI * 2 / count;
        surfaces = new PanelSurface[count];
        unfold = new OrbitSpring[count];
        for (int i = 0; i < count; i++) {
            surfaces[i] = new PanelSurface();
            unfold[i] = new OrbitSpring(0f, 120f, 15f);
        }
        searchBar = new OrbitSearchBar(view, () -> setOverview(false));
        overlay = new OrbitOverlay(this, host, searchBar);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            yaw0 = player.getYRot();
            pitch0 = player.getXRot();
        }
        island = OrbitIsland.current();
        clone = buildClone();
        OrbitIsland from = OrbitIsland.arrive(island);
        if (from != null) cinematic = new TravelCinematic(from, island, dev.aether.renderer.SkinFaceProvider::render);
        String first = OrbitIsland.initialCategory(MacroCatalog.lastStarted().map(MacroCatalog.Entry::id).orElse(null), island);
        int firstIndex = Math.max(0, categories.indexOf(first));
        ring.snap(firstIndex);
        if (!categories.isEmpty()) {
            focused = categories.get(firstIndex);
            view.orbitFocus(focused);
        }
        view.orbitPlotHooks(new dev.aether.ui.orbit.panel.PlotHooks() {
            @Override
            public void paintThumbnail(dev.aether.ui.gui.GuiCanvas canvas, dev.aether.ui.settings.PlotSetting setting,
                                       dev.aether.ui.gui.Rect area) {
                var model = new dev.aether.ui.gui.plot.PlotPickerModel(setting);
                var facts = dev.aether.ui.gui.plot.GardenFacts.read(dev.aether.ui.gui.plot.GardenPlotData.active());
                float time = seconds();
                var thumb = OrbitPlotScreen.thumbView(area.x(), area.y(), area.w(), area.h(), OrbitPlotScreen.thumbYaw(time));
                canvas.legacy(nvg -> PlotDiorama.draw(nvg, thumb, plot -> model.look(plot, facts), null, -1, time, false,
                        dev.aether.ui.gui.Palette.fromTheme().accent(), 1f));
            }

            @Override
            public void open(dev.aether.ui.settings.PlotSetting setting, dev.aether.ui.gui.Rect area) {
                openPlotScreen(setting, area);
            }
        });
    }

    // -- simulation --------------------------------------------------------------------------------------------

    private void simulate() {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.05f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        clock += dt;
        lastDt = dt;
        wheelLock = Math.max(0f, wheelLock - dt);

        if (draggingRing) {
            momentum *= (float) Math.pow(0.02, dt);
        } else if (Math.abs(momentum) > 0.01f) {
            ring.t = Math.round(ring.x + momentum * 0.35f);
            momentum = 0f;
        }
        if (!draggingRing) ring.step(dt);
        zoom.step(dt);
        expand.t = view.orbitModuleOpen() ? 1f : 0f;
        expand.step(dt);
        double[][] lean = OrbitRig.lean(activeCategory());
        float lk = (1f - zoom.x) * (view.orbitModuleOpen() ? 0.55f : 1f);
        for (int k = 0; k < 3; k++) {
            leanPos[k].t = (float) lean[0][k] * lk;
            leanLook[k].t = (float) lean[1][k] * lk;
            leanPos[k].step(dt);
            leanLook[k].step(dt);
        }
        for (OrbitSpring u : unfold) u.step(dt);
        if (cinematic != null) {
            cinematic.step(dt);
            if (!cinematic.revealing()) hold = Math.max(hold, 0.05f);
        }

        if (state == State.OPENING) {
            if (hold > 0) hold -= dt;
            else openT += dt / openSeconds;
            for (int i = 0; i < count; i++) {
                double ao = Math.abs(wrap(i - ring.t));
                if (openT > 0.32f + ao * 0.1f) unfold[i].t = 1f;
            }
            if (openT >= 1f) {
                openT = 1f;
                state = State.OPEN;
            }
        } else if (state == State.CLOSING) {
            closeT += dt / closeSeconds;
            for (OrbitSpring u : unfold) u.t = 0f;
            if (closeT >= 1f) {
                finishClose();
                return;
            }
        }
        syncFocus();
        computeLayout();
    }

    // the ring and the panel ui each move the focus: spinning opens that category, and a search hit or link
    // that lands in another category spins the ring to it
    private void syncFocus() {
        String located = view.orbitCategory();
        if (located != null && !located.equals(focused) && categories.contains(located)) {
            spinTo(categories.indexOf(located));
            focused = located;
            return;
        }
        String active = activeCategory();
        if (active != null && !active.equals(focused)) {
            view.orbitFocus(active);
            focused = active;
        }
    }

    String activeCategory() {
        if (categories.isEmpty()) return null;
        return categories.get(activeIndex());
    }

    int activeIndex() {
        return Math.floorMod(Math.round(ring.t), count);
    }

    float zoomAmount() {
        return zoom.x;
    }

    float hudAlpha() {
        return switch (state) {
            case OPEN -> 1f;
            case OPENING -> OrbitRig.clamp((openT - 0.55f) / 0.35f, 0f, 1f);
            case CLOSING -> 1f - OrbitRig.clamp(closeT * 2.5f, 0f, 1f);
        };
    }

    private double wrap(double o) {
        return OrbitLayout.wrap(o, count);
    }

    private void computeLayout() {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null) return;
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 feet = player.getPosition(partial);
        float e = switch (state) {
            case OPENING -> OrbitRig.easeInOut(OrbitRig.clamp(openT, 0f, 1f));
            case OPEN -> 1f;
            case CLOSING -> 1f - OrbitRig.easeInOut(OrbitRig.clamp(closeT, 0f, 1f));
        };
        float[] unfoldNow = new float[count];
        for (int i = 0; i < count; i++) unfoldNow[i] = unfold[i].x;
        double[] lp = {leanPos[0].x, leanPos[1].x, leanPos[2].x};
        double[] ll = {leanLook[0].x, leanLook[1].x, leanLook[2].x};
        Vector3d anchor = sceneAnchor(feet);
        Vector3d eye = new Vector3d(feet.x, feet.y + player.getEyeHeight(), feet.z);
        Vector3d eyeLook = OrbitLayout.lookPoint(eye, yaw0, pitch0);
        layout = OrbitLayout.compute(new OrbitLayout.Input(anchor, sceneYaw(), pitch0,
                player.getEyeHeight(), client.options.fov().get(), count, ring.x, zoom.x, expand.x, e,
                state == State.OPEN, clock, unfoldNow, activeIndex(), lp, ll, client.getWindow().getHeight(),
                eye, eyeLook));
        OrbitLayout.Camera cam = layout.camera();
        OrbitCamera.set(cam.pos().x, cam.pos().y, cam.pos().z, cam.look().x, cam.look().y, cam.look().z, cam.fov());
    }

    private Vector3d rigToWorld(Vector3d anchor, double rx, double ry, double rz) {
        double yaw = Math.toRadians(sceneYaw());
        return new Vector3d(anchor.x + Math.cos(yaw) * rx - Math.sin(yaw) * rz, anchor.y + ry,
                anchor.z + Math.sin(yaw) * rx + Math.cos(yaw) * rz);
    }

    // the preset garden around the player, meshed once; null keeps the real world behind the menu
    private SceneClone.Mesh buildClone() {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (client.level == null || player == null) return null;
        Vector3d anchor = sceneAnchor(player.position());
        int ox = (int) Math.floor(anchor.x), oy = (int) Math.floor(anchor.y + 1e-3), oz = (int) Math.floor(anchor.z);
        Vector3d lens = rigToWorld(anchor, OrbitRig.TP_POS.x, 0, OrbitRig.TP_POS.z);
        double cx = lens.x - ox, cz = lens.z - oz;
        PresetGarden source = new PresetGarden(ox, oy, oz, sceneYaw());
        hotspots = new SceneHotspots(source, anchor);
        try {
            return SceneClone.build(source, ox, oy, oz, 40, (dx, dz) -> {
                double toLens = (dx + 0.5 - cx) * (dx + 0.5 - cx) + (dz + 0.5 - cz) * (dz + 0.5 - cz);
                if (toLens < 16) return 0;
                return dx * dx + dz * dz <= 22 * 22 ? 1 : 64;
            });
        } catch (RuntimeException | LinkageError e) {
            Aether.LOGGER.error("Orbit menu could not build its garden", e);
            return null;
        }
    }

    private void renderScene() {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || layout == null) return;
        Vector3d anchor = sceneAnchor(player.getPosition(client.getDeltaTracker().getGameTimeDeltaPartialTick(true)));
        double yaw = Math.toRadians(sceneYaw());
        OrbitLayout.Camera cam = layout.camera();
        Vector3d dir = mouseX < 0 ? new Vector3d(cam.forward()) : rayDirection(mouseX, mouseY);
        Vector3d target = new Vector3d(cam.pos()).fma(14, dir).sub(anchor);
        float lx = (float) (target.x * Math.cos(yaw) + target.z * Math.sin(yaw));
        float lz = (float) (-target.x * Math.sin(yaw) + target.z * Math.cos(yaw));
        figure.lookAt(lx, (float) target.y, lz, lastDt);
        if (figureBuffer == null) figureBuffer = new SceneClone.Buffer(512);
        figureBuffer.reset();
        var eye = client.gameRenderer.getMainCamera().position();
        org.joml.Matrix4f toWorld = new org.joml.Matrix4f()
                .translate((float) (anchor.x - eye.x), (float) (anchor.y - eye.y), (float) (anchor.z - eye.z))
                .rotateY((float) -yaw);
        var skin = player.getSkin();
        figure.build(figureBuffer, toWorld, skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM, clock);
        boolean crimson = island == OrbitIsland.CRIMSON_ISLE;
        var frame = new SceneRenderer.Frame(anchor.x, anchor.y, anchor.z, sceneYaw(), figureBuffer,
                skin.body().texturePath(), crimson ? 0xFF2A0A10 : 0xFF6FA2E8, crimson ? 0xFF7A2E1C : 0xFFC7DDF5);
        sceneRenderer.draw(clone, frame);
        renderWorld();
        sceneRenderer.sealDepth(frame);
    }

    // the ring's centre on the ground, at the player's feet
    private Vector3d sceneAnchor(Vec3 feet) {
        return new Vector3d(feet.x, feet.y, feet.z);
    }

    // the facing on open snapped to a quarter turn, which the preset farm is laid out along
    private float sceneYaw() {
        return Math.round(yaw0 / 90f) * 90f;
    }

    private static float seconds() {
        return (System.nanoTime() % 3_600_000_000_000L) / 1_000_000_000f;
    }

    private void openPlotScreen(dev.aether.ui.settings.PlotSetting setting, dev.aether.ui.gui.Rect area) {
        OrbitLayout.Placement active = activePlacement();
        if (active == null || layout == null) return;
        float[] a = projectLocal(active, area.x(), area.y());
        float[] b = projectLocal(active, area.right(), area.bottom());
        float[] rect = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])};
        plotScreen = new OrbitPlotScreen(setting, rect, OrbitPlotScreen.thumbYaw(seconds()));
        plotNanos = System.nanoTime();
    }

    // a point in panel design units to gui-scaled screen coordinates through the orbit camera
    private float[] projectLocal(OrbitLayout.Placement p, float lx, float ly) {
        double u = lx / p.designW() - 0.5, v = 0.5 - ly / p.designH();
        Vector3d world = new Vector3d(p.center()).fma(u * p.width(), p.right()).fma(v * p.height(), p.up());
        OrbitLayout.Camera cam = layout.camera();
        Vector3d rel = world.sub(cam.pos());
        double z = rel.dot(cam.forward());
        double t = Math.tan(Math.toRadians(cam.fov()) / 2);
        double aspect = (double) width / height;
        double ndcX = rel.dot(cam.right()) / (z * t * aspect);
        double ndcY = rel.dot(cam.up()) / (z * t);
        return new float[]{(float) ((ndcX + 1) / 2 * width), (float) ((1 - ndcY) / 2 * height)};
    }

    // -- rendering ---------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float partialTick) {
        simulate();
        graphics.requestCursor(cursor());
        AetherRenderQueue.enqueue(this::renderOverlayFrame);
    }

    // the pointer shape for what is under it: the front panel's own regions, a hand on anything that spins the
    // ring or opens something, and a sideways arrow while the ring is dragged
    private CursorType cursor() {
        if (cinematic != null && !cinematic.revealing() || plotScreen != null || mouseX < 0) return CursorTypes.ARROW;
        if (draggingRing) return CursorTypes.RESIZE_EW;
        if (overlay.hovering(mouseX, mouseY)) return CursorTypes.POINTING_HAND;
        if (pressedPanel) return panelCursor();
        OrbitLayout.Placement hit = pick(mouseX, mouseY);
        if (hit == null) return hotspots != null && hotspots.hovered() != null ? CursorTypes.POINTING_HAND : CursorTypes.ARROW;
        if (overview() || !hit.active()) return CursorTypes.POINTING_HAND;
        return panelCursor();
    }

    private CursorType panelCursor() {
        return switch (view.cursor()) {
            case DEFAULT -> CursorTypes.ARROW;
            case HAND -> CursorTypes.POINTING_HAND;
            case IBEAM -> CursorTypes.IBEAM;
            case CROSSHAIR -> CursorTypes.CROSSHAIR;
            case RESIZE_EW -> CursorTypes.RESIZE_EW;
            case RESIZE_NS -> CursorTypes.RESIZE_NS;
            case MOVE -> CursorTypes.RESIZE_ALL;
            case NOT_ALLOWED -> CursorTypes.NOT_ALLOWED;
        };
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
    }

    // called from the level pass; draws every panel into its texture, then the panels into the world
    // called at the end of the level pass. the level always renders, so its chunk uploads never pile up; with a
    // copy of the scene the menu paints the copy over it before the panels
    public static void renderWorldIfOpen() {
        if (Minecraft.getInstance().screen instanceof OrbitScreen screen) {
            try {
                if (screen.clone != null && !screen.sceneRenderer.failed()) screen.renderScene();
                else screen.renderWorld();
            } catch (RuntimeException | LinkageError e) {
                Aether.LOGGER.error("Orbit menu world pass failed", e);
            }
        }
    }

    private void renderWorld() {
        if (layout == null) return;
        float k = (float) OrbitLayout.pixelRatio(Minecraft.getInstance().getWindow().getHeight());
        float z = zoom.x;
        List<OrbitWorldRenderer.Quad> quads = new ArrayList<>();
        for (OrbitLayout.Placement p : layout.placements()) {
            if (p.alpha() <= 0.001f) continue;
            String id = categories.get(p.index());
            if (p.active() && z < 0.5f) {
                float[] local = localMouse(p);
                surfaces[p.index()].render(p.designW(), p.designH(), k,
                        nvg -> view.renderOrbitActive(nvg, p.designW(), p.designH(), local[0], local[1], id));
            } else {
                float ratio = z > 0.5f ? k * 0.6f : k * 0.55f;
                surfaces[p.index()].render(p.designW(), p.designH(), ratio,
                        nvg -> view.renderOrbitPassive(nvg, p.designW(), p.designH(), id, z));
            }
            quads.add(new OrbitWorldRenderer.Quad(p.corner(-1, 1), p.corner(1, 1), p.corner(1, -1), p.corner(-1, -1),
                    surfaces[p.index()].texture(), p.alpha(), p.dim(), p.normal(), p.bend()));
        }
        boolean safetyFront = "safety".equals(activeCategory()) && z < 0.5f && state != State.CLOSING;
        failsafeRing.step(lastDt, safetyFront);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            Vec3 feet = player.getPosition(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
            failsafeRing.appendQuads(quads, view.orbitFailsafes(), view.orbitHoveredFailsafe(),
                    sceneAnchor(feet), layout.camera(), clock);
            settingPreview.step(lastDt, view.orbitHover(), z < 0.5f && state != State.CLOSING && plotScreen == null);
            if (settingPreview.showing()) {
                var world = new SettingPreview.World(sceneAnchor(feet), player.getEyeHeight(), sceneYaw(),
                        pitch0, dev.aether.ui.gui.plot.GardenFacts.read(dev.aether.ui.gui.plot.GardenPlotData.active()),
                        SettingPreview.liveRewarps());
                settingPreview.appendQuads(quads, world, layout.camera(), clock);
            }
        }
        if (hotspots != null) {
            boolean free = mouseX >= 0 && !draggingRing && !pressedPanel && plotScreen == null && state == State.OPEN
                    && !overlay.hovering(mouseX, mouseY) && pick(mouseX, mouseY) == null;
            if (free) hotspots.pick(layout.camera().pos(), rayDirection(mouseX, mouseY));
            else hotspots.clear();
            hotspots.step(lastDt);
            hotspots.appendQuads(quads, layout.camera());
        }
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        quads.sort(Comparator.comparingDouble((OrbitWorldRenderer.Quad q) -> -distanceSq(q, eye)));
        renderer.draw(quads);
    }

    private static double distanceSq(OrbitWorldRenderer.Quad q, Vec3 eye) {
        double cx = (q.topLeft().x + q.bottomRight().x) / 2 - eye.x;
        double cy = (q.topLeft().y + q.bottomRight().y) / 2 - eye.y;
        double cz = (q.topLeft().z + q.bottomRight().z) / 2 - eye.z;
        return cx * cx + cy * cy + cz * cz;
    }

    private void renderOverlayFrame() {
        if (Minecraft.getInstance().screen != this) return;
        if (!NanoVGManager.isInitialized()) NanoVGManager.init();
        NanoVGManager.beginFrame(width, height);
        try {
            NVGRenderer nvg = NanoVGManager.getRenderer();
            nvg.setTextScale(1f);
            overlay.render(nvg, width, height, (float) mouseX, (float) mouseY);
            if (plotScreen != null) {
                long now = System.nanoTime();
                float dt = Math.min(0.05f, (now - plotNanos) / 1_000_000_000f);
                plotNanos = now;
                plotScreen.render(nvg, width, height, (float) mouseX, (float) mouseY, dt, seconds());
                if (plotScreen.finished()) plotScreen = null;
            }
            if (cinematic != null) {
                cinematic.render(nvg, width, height);
                if (cinematic.finished()) cinematic = null;
            }
            dev.aether.notification.NotificationRenderer.render(nvg, width, height);
        } finally {
            NanoVGManager.endFrame();
        }
    }

    // -- picking -----------------------------------------------------------------------------------------------

    private Vector3d rayDirection(double guiX, double guiY) {
        return OrbitLayout.ray(layout.camera(), guiX, guiY, width, height);
    }

    // panel-local design coordinates of the cursor on p, or {-1, -1} when it misses
    private float[] localMouse(OrbitLayout.Placement p) {
        if (mouseX < 0) return new float[]{-1f, -1f};
        float[] hit = intersect(p, rayDirection(mouseX, mouseY));
        return hit == null ? new float[]{-1f, -1f} : hit;
    }

    private float[] intersect(OrbitLayout.Placement p, Vector3d dir) {
        return OrbitLayout.hit(layout.camera(), p, dir, false);
    }

    // drags keep tracking past the panel edge, so sliders don't drop the knob when the cursor overshoots
    private float[] projectOntoPlane(OrbitLayout.Placement p, double gx, double gy) {
        float[] hit = OrbitLayout.hit(layout.camera(), p, rayDirection(gx, gy), true);
        return hit == null ? new float[]{-1f, -1f} : hit;
    }

    // nearest panel under the cursor
    private OrbitLayout.Placement pick(double gx, double gy) {
        if (layout == null) return null;
        Vector3d dir = rayDirection(gx, gy);
        OrbitLayout.Placement best = null;
        double bestDist = Double.MAX_VALUE;
        for (OrbitLayout.Placement p : layout.placements()) {
            if (p.alpha() < 0.2f || intersect(p, dir) == null) continue;
            double d = new Vector3d(p.center()).distanceSquared(layout.camera().pos());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private OrbitLayout.Placement activePlacement() {
        if (layout == null) return null;
        for (OrbitLayout.Placement p : layout.placements()) if (p.active()) return p;
        return null;
    }

    // -- input -------------------------------------------------------------------------------------------------

    private void activate(SceneHotspots.Hotspot spot) {
        String target = spot.target();
        if (target.equals("wave")) {
            figure.wave();
        } else if (target.startsWith("page:")) {
            view.orbitOpenPage(target.substring(5));
            setOverview(false);
        } else if (target.startsWith("category:")) {
            int index = categories.indexOf(target.substring(9));
            if (index >= 0) spinTo(index);
            setOverview(false);
        }
    }

    private boolean skipCinematic() {
        if (cinematic == null || cinematic.revealing()) return false;
        cinematic.skip();
        return true;
    }

    void spinTo(int index) {
        int base = Math.round(ring.t);
        int cur = Math.floorMod(base, count);
        int d = Math.floorMod(index - cur, count);
        if (d > count / 2) d -= count;
        ring.t = base + d;
        momentum = 0f;
    }

    void spinBy(int d) {
        ring.t = Math.round(ring.t) + d;
        momentum = 0f;
    }

    void setOverview(boolean on) {
        zoom.t = on ? 1f : 0f;
    }

    boolean overview() {
        return zoom.t > 0.5f;
    }

    String breadcrumb() {
        String cat = activeCategory();
        if (cat == null) return "";
        String name = view.orbitCategoryName(cat);
        String page = view.orbitModuleOpen() ? view.orbitOpenPageName() : null;
        return overview() ? "" : page == null ? name : name + "  ›  " + page;
    }

    List<String> categoryIds() {
        return categories;
    }

    PanelView view() {
        return view;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        mouseX = click.x();
        mouseY = click.y();
        if (state == State.CLOSING) return true;
        if (skipCinematic()) return true;
        if (plotScreen != null) return plotScreen.click(click.x(), click.y(), click.button());
        if (overlay.click(click.x(), click.y(), click.button())) return true;
        OrbitLayout.Placement hit = pick(click.x(), click.y());
        if (hit != null) {
            if (overview()) {
                spinTo(hit.index());
                setOverview(false);
                return true;
            }
            if (hit.active()) {
                float[] local = intersect(hit, rayDirection(click.x(), click.y()));
                if (local != null) {
                    pressedPanel = true;
                    view.pointerPressed(new PointerInput(local[0], local[1], click.button(), click.modifiers(),
                            doubled ? 2 : 1));
                }
                return true;
            }
            spinTo(hit.index());
            return true;
        }
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // a press on the farm still starts a spin; it only counts as a click if the mouse stays put
            pressedHotspot = hotspots == null ? null : hotspots.hovered();
            pressX = click.x();
            pressY = click.y();
            draggingRing = true;
            dragLastX = click.x();
            momentum = 0f;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
        mouseX = click.x();
        mouseY = click.y();
        if (plotScreen != null) {
            plotScreen.drag(click.x(), click.y());
            return true;
        }
        if (draggingRing) {
            double delta = (click.x() - dragLastX) / width * count * 0.9;
            dragLastX = click.x();
            ring.x -= (float) delta;
            ring.t = ring.x;
            ring.v = 0f;
            momentum = momentum * 0.6f - (float) delta * 12f;
            return true;
        }
        if (pressedPanel) {
            OrbitLayout.Placement active = activePlacement();
            if (active != null) {
                float[] local = projectOntoPlane(active, click.x(), click.y());
                view.pointerDragged(new PointerInput(local[0], local[1], click.button(), click.modifiers(), 1));
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        mouseX = click.x();
        mouseY = click.y();
        if (plotScreen != null) {
            plotScreen.release(click.x(), click.y());
            return true;
        }
        if (draggingRing) {
            draggingRing = false;
            ring.t = Math.round(ring.x + momentum * 0.35f);
            momentum = 0f;
            SceneHotspots.Hotspot spot = pressedHotspot;
            pressedHotspot = null;
            if (spot != null && Math.hypot(click.x() - pressX, click.y() - pressY) < 4) activate(spot);
            return true;
        }
        if (pressedPanel) {
            pressedPanel = false;
            OrbitLayout.Placement active = activePlacement();
            if (active != null) {
                float[] local = projectOntoPlane(active, click.x(), click.y());
                view.pointerReleased(new PointerInput(local[0], local[1], click.button(), click.modifiers(), 1));
            }
        }
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) {
        mouseX = x;
        mouseY = y;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        mouseX = x;
        mouseY = y;
        if (cinematic != null && !cinematic.revealing()) return true;
        if (searchBar.over(x, y)) {
            searchBar.scroll(scrollY);
            return true;
        }
        if (hasControlDown()) {
            setOverview(scrollY < 0);
            return true;
        }
        OrbitLayout.Placement hit = pick(x, y);
        if (hit != null && hit.active() && !overview() && !hasShiftDown()) {
            float[] local = intersect(hit, rayDirection(x, y));
            if (local != null && view.scrolled(local[0], local[1], scrollX, scrollY)) return true;
        }
        if (wheelLock <= 0f && scrollY != 0) {
            spinBy(scrollY > 0 ? -1 : 1);
            wheelLock = 0.12f;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        KeyInput input = new KeyInput(key, event.scancode(), event.modifiers(), hasControlDown(), hasShiftDown(),
                hasAltDown());
        if (skipCinematic()) return true;
        if (plotScreen != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) plotScreen.close();
            return true;
        }
        if (searchBar.isOpen()) {
            if (hasControlDown() && key == GLFW.GLFW_KEY_V) {
                searchBar.type(Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("\\s+", " "));
                return true;
            }
            return searchBar.key(key, hasControlDown(), hasShiftDown());
        }
        if (hasControlDown() && key == GLFW.GLFW_KEY_F && !view.orbitTyping()) {
            searchBar.open("");
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if ((view.orbitOverlayOpen() || view.orbitTyping()) && view.keyPressed(input)) return true;
            if (view.orbitModuleOpen()) {
                view.orbitBack();
            } else if (!overview()) {
                setOverview(true);
            } else {
                beginClose();
            }
            return true;
        }
        if (view.keyPressed(input)) return true;
        if (key == GLFW.GLFW_KEY_TAB) {
            setOverview(!overview());
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            spinBy(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            spinBy(1);
            return true;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9 && key - GLFW.GLFW_KEY_1 < count) {
            spinTo(key - GLFW.GLFW_KEY_1);
            setOverview(false);
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && hasControlDown()) {
            MacroCatalog.lastStarted().ifPresent(MacroCatalog::start);
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!event.isAllowedChatCharacter() || cinematic != null && !cinematic.revealing()) return true;
        String typed = Character.toString(event.codepoint());
        if (searchBar.isOpen()) {
            searchBar.type(typed);
        } else if (view.orbitTyping()) {
            view.charTyped(typed);
        } else if (typed.equals("/")) {
            searchBar.open("");
        } else if (Character.isLetter(event.codepoint()) && plotScreen == null) {
            searchBar.type(typed);
        }
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void requestClose() {
        beginClose();
    }

    void beginClose() {
        if (state == State.CLOSING) return;
        view.close();
        state = State.CLOSING;
        closeT = 0f;
        setOverview(false);
    }

    private void finishClose() {
        OrbitCamera.clear();
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void removed() {
        OrbitCamera.clear();
        view.close();
        for (PanelSurface surface : surfaces) surface.close();
        failsafeRing.close();
        settingPreview.close();
        if (hotspots != null) hotspots.close();
        sceneRenderer.close();
        if (clone != null) clone.close();
        clone = null;
        if (figureBuffer != null) figureBuffer.free();
        figureBuffer = null;
        renderer.close();
        super.removed();
    }

    private static boolean down(int a, int b) {
        var window = Minecraft.getInstance().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, a)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, b);
    }

    private static boolean hasControlDown() {
        return down(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)
                || down(GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER);
    }

    private static boolean hasShiftDown() {
        return down(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static boolean hasAltDown() {
        return down(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    static float uiScale() {
        return Theme.UI_SCALE;
    }
}
