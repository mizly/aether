package dev.aether.ui.orbit;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.aether.config.AetherConfig;
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
    private final PanelSurface shadowSurface = new PanelSurface();
    private boolean shadowDrawn;
    private final SettingPreview settingPreview = new SettingPreview();
    private float lastDt;
    private final OrbitOverlay overlay;

    private final OrbitSpring ring = new OrbitSpring(0f, 130f, 15.5f);
    private final OrbitSpring zoom = new OrbitSpring(1f, 60f, 13f);
    private final OrbitSpring expand = new OrbitSpring(0f, 120f, 16f);
    private final OrbitSpring tiltX = new OrbitSpring(0f, 60f, 12f);
    private final OrbitSpring tiltY = new OrbitSpring(0f, 60f, 12f);
    private float[] frontMouse = {-1f, -1f};

    private State state = State.OPENING;
    private float openT;
    private float closeT;
    private float hold = 0.05f;
    private long lastNanos;
    private float clock;
    private float momentum;
    private boolean draggingRing;
    private double dragLastX;
    private double dragStartX;
    private boolean ringMoved;
    private boolean pressedPanel;
    private float wheelLock;

    private float yaw0;
    private float pitch0;
    // where you stood when the menu opened: the farm, ring and camera stay put here even if you move after
    private Vec3 home = Vec3.ZERO;
    private float homeEyeHeight = 1.62f;
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
    // the middle of the block the farm was built around, on its grass: the skits play here so they sit on the grid
    private final Vector3d stage = new Vector3d();
    private final SceneRenderer sceneRenderer = new SceneRenderer();
    private final PlayerFigure figure = new PlayerFigure();
    private final SceneActors actors = new SceneActors(dev.aether.renderer.PestHeads::texture);
    private SceneClone.Buffer figureBuffer;
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
            home = player.position();
            homeEyeHeight = player.getEyeHeight();
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
        // the flight home lands on wherever your eye is now, so the hand back to the game's camera is seamless
        Vec3 feet = state == State.CLOSING ? player.getPosition(partial) : home;
        float e = switch (state) {
            case OPENING -> OrbitRig.easeInOut(OrbitRig.clamp(openT, 0f, 1f));
            case OPEN -> 1f;
            case CLOSING -> 1f - OrbitRig.easeInOut(OrbitRig.clamp(closeT, 0f, 1f));
        };
        float[] unfoldNow = new float[count];
        for (int i = 0; i < count; i++) unfoldNow[i] = unfold[i].x;
        Vector3d anchor = sceneAnchor(home);
        Vector3d eye = new Vector3d(feet.x, feet.y + player.getEyeHeight(), feet.z);
        Vector3d eyeLook = OrbitLayout.lookPoint(eye, yaw0, pitch0);
        layout = OrbitLayout.compute(new OrbitLayout.Input(anchor, sceneYaw(), pitch0,
                homeEyeHeight, client.options.fov().get(), count, ring.x, zoom.x, expand.x, e,
                state == State.OPEN, clock, unfoldNow, activeIndex(), client.getWindow().getHeight(),
                eye, eyeLook));
        layout = tiltFront(layout);
        OrbitLayout.Camera cam = layout.camera();
        OrbitCamera.set(cam.pos().x, cam.pos().y, cam.pos().z, cam.look().x, cam.look().y, cam.look().z, cam.fov());
    }

    // the front panel leans a few degrees toward the cursor, the side under it coming forward
    private OrbitLayout.Result tiltFront(OrbitLayout.Result result) {
        OrbitLayout.Placement[] placements = result.placements();
        for (int i = 0; i < placements.length; i++) {
            OrbitLayout.Placement p = placements[i];
            if (!p.active()) continue;
            boolean over = frontMouse[0] >= 0 && !draggingRing && zoom.x < 0.5f;
            tiltX.t = over ? OrbitRig.clamp(frontMouse[0] / p.designW() * 2f - 1f, -1f, 1f) : 0f;
            tiltY.t = over ? OrbitRig.clamp(frontMouse[1] / p.designH() * 2f - 1f, -1f, 1f) : 0f;
            tiltX.step(lastDt);
            tiltY.step(lastDt);
            placements[i] = OrbitLayout.tilt(p, Math.toRadians(3.0) * tiltX.x, Math.toRadians(2.2) * tiltY.x);
        }
        return result;
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
        Vector3d anchor = sceneAnchor(home);
        int ox = (int) Math.floor(anchor.x), oy = (int) Math.floor(anchor.y + 1e-3), oz = (int) Math.floor(anchor.z);
        Vector3d lens = rigToWorld(anchor, OrbitRig.TP_POS.x, 0, OrbitRig.TP_POS.z);
        double cx = lens.x - ox, cz = lens.z - oz;
        PresetGarden source = new PresetGarden(ox, oy, oz, sceneYaw());
        stage.set(ox + 0.5, oy, oz + 0.5);
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
        Vector3d anchor = sceneAnchor(home);
        double yaw = Math.toRadians(sceneYaw());
        OrbitLayout.Camera cam = layout.camera();
        Vector3d dir = mouseX < 0 ? new Vector3d(cam.forward()) : rayDirection(mouseX, mouseY);
        Vector3d target = new Vector3d(cam.pos()).fma(14, dir).sub(stage);
        float lx = (float) (target.x * Math.cos(yaw) + target.z * Math.sin(yaw));
        float lz = (float) (-target.x * Math.sin(yaw) + target.z * Math.cos(yaw));
        var focus = overview() || state != State.OPEN ? null : view.orbitFocusModule();
        actors.update(lastDt, clock, focus == null ? SceneActors.Inputs.NONE : new SceneActors.Inputs(focus.name(),
                focus.enabled(), AetherConfig.VISITOR_THRESHOLD.get(), AetherConfig.PEST_THRESHOLD.get(),
                AetherConfig.VISITOR_MAX_PURCHASE_LIMIT.get() / 1e6), figure);
        figure.lookAt(lx, (float) target.y, lz, lastDt);
        if (figureBuffer == null) figureBuffer = new SceneClone.Buffer(512);
        figureBuffer.reset();
        var eye = client.gameRenderer.getMainCamera().position();
        org.joml.Matrix4f toWorld = new org.joml.Matrix4f()
                .translate((float) (stage.x - eye.x), (float) (stage.y - eye.y), (float) (stage.z - eye.z))
                .rotateY((float) -yaw);
        var skin = player.getSkin();
        figure.build(figureBuffer, toWorld, skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM, clock);
        boolean crimson = island == OrbitIsland.CRIMSON_ISLE;
        Vector3d lens = new Vector3d(cam.pos()).sub(stage);
        Vector3d camLocal = new Vector3d(lens.x * Math.cos(yaw) + lens.z * Math.sin(yaw), lens.y,
                -lens.x * Math.sin(yaw) + lens.z * Math.cos(yaw));
        var draws = actors.build(toWorld, camLocal, new org.joml.Vector3f(cam.right()), new org.joml.Vector3f(cam.up()), figure);
        var frame = new SceneRenderer.Frame(anchor.x, anchor.y, anchor.z, sceneYaw(), figureBuffer,
                skin.body().texturePath(), crimson ? 0xFF2A0A10 : 0xFF6FA2E8, crimson ? 0xFF7A2E1C : 0xFFC7DDF5,
                draws, null);
        if (!sceneRenderer.draw(clone, frame)) {
            renderWorld(0, 0, 0);
            return;
        }
        renderWorld(sceneRenderer.framebuffer(), sceneRenderer.width(), sceneRenderer.height());
        AetherRenderQueue.enqueueBeforeGui(() -> {
            if (Minecraft.getInstance().screen == this) sceneRenderer.present();
        });
    }

    // the ring's centre: the middle of the block you stand in, where the farm puts your figure, so the shot is the
    // same wherever in the block you opened the menu
    private Vector3d sceneAnchor(Vec3 feet) {
        return new Vector3d(Math.floor(feet.x) + 0.5, Math.floor(feet.y + 1e-3), Math.floor(feet.z) + 0.5);
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
        // the picker takes the release of the press that opened it, which would leave config saving paused
        if (pressedPanel) {
            pressedPanel = false;
            view.pointerCancelled();
        }
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
        if (hit == null) return CursorTypes.ARROW;
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
                else screen.renderWorld(0, 0, 0);
            } catch (RuntimeException | LinkageError e) {
                Aether.LOGGER.error("Orbit menu world pass failed", e);
            }
        }
    }

    private void renderWorld(int into, int intoWidth, int intoHeight) {
        if (layout == null) return;
        float k = (float) OrbitLayout.pixelRatio(Minecraft.getInstance().getWindow().getHeight());
        float z = zoom.x;
        List<OrbitWorldRenderer.Quad> quads = new ArrayList<>();
        OrbitWorldRenderer.Quad front = null;
        for (OrbitLayout.Placement p : layout.placements()) {
            if (p.alpha() <= 0.001f) continue;
            String id = categories.get(p.index());
            if (p.active() && z < 0.5f) {
                float[] local = localMouse(p);
                frontMouse = local;
                surfaces[p.index()].render(p.designW(), p.designH(), k,
                        nvg -> view.renderOrbitActive(nvg, p.designW(), p.designH(), local[0], local[1], id));
            } else {
                float ratio = z > 0.5f ? k * 0.6f : k * 0.55f;
                surfaces[p.index()].render(p.designW(), p.designH(), ratio,
                        nvg -> view.renderOrbitPassive(nvg, p.designW(), p.designH(), id, z));
            }
            shadow(quads, p);
            PanelSurface surface = surfaces[p.index()];
            float radius = dev.aether.ui.orbit.panel.PanelView.ORBIT_RADIUS;
            var quad = new OrbitWorldRenderer.Quad(p.corner(-1, 1), p.corner(1, 1), p.corner(1, -1), p.corner(-1, -1),
                    surface.texture(), p.alpha(), p.dim(), surface.uMax(), surface.vMax(), radius / p.designW(),
                    radius / p.designH());
            if (p.active() && z < 0.5f) front = quad;
            else quads.add(quad);
        }
        boolean safetyFront = "safety".equals(activeCategory()) && z < 0.5f && state != State.CLOSING;
        failsafeRing.step(lastDt, safetyFront);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            Vec3 feet = home;
            failsafeRing.appendQuads(quads, view.orbitFailsafes(), view.orbitHoveredFailsafe(),
                    sceneAnchor(feet), layout.camera(), clock);
            settingPreview.step(lastDt, view.orbitHover(), z < 0.5f && state != State.CLOSING && plotScreen == null);
            if (settingPreview.showing()) {
                var world = new SettingPreview.World(sceneAnchor(feet), homeEyeHeight, sceneYaw(),
                        pitch0, dev.aether.ui.gui.plot.GardenFacts.read(dev.aether.ui.gui.plot.GardenPlotData.active()),
                        SettingPreview.liveRewarps());
                settingPreview.appendQuads(quads, world, layout.camera(), clock);
            }
        }
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        quads.sort(Comparator.comparingDouble((OrbitWorldRenderer.Quad q) -> -distanceSq(q, eye)));
        // the panel you are reading goes on last, so no shadow, preview or neighbour drawn by distance can shade it
        if (front != null) quads.add(front);
        renderer.draw(quads, into, intoWidth, intoHeight);
    }

    // a soft dark pool behind each panel, a little larger and lower, so the panels float above the farm
    private void shadow(List<OrbitWorldRenderer.Quad> quads, OrbitLayout.Placement p) {
        if (!shadowDrawn) {
            shadowSurface.render(128f, 128f, 1f, nvg -> nvg.boxGradient(24f, 24f, 80f, 80f, 18f, 22f, 0x8C000000, 0x00000000));
            shadowDrawn = true;
        }
        Vector3d back = new Vector3d(p.normal()).mul(-0.06).fma(-p.height() * 0.04, p.up());
        // the gradient's dark core is 80 of the texture's 128 px, so this scale lines the core up with the panel
        double sx = 128.0 / 80.0, sy = 128.0 / 80.0;
        Vector3d c = new Vector3d(p.center()).add(back);
        Vector3d r = new Vector3d(p.right()).mul(p.width() / 2 * sx), u = new Vector3d(p.up()).mul(p.height() / 2 * sy);
        quads.add(new OrbitWorldRenderer.Quad(new Vector3d(c).sub(r).add(u), new Vector3d(c).add(r).add(u),
                new Vector3d(c).add(r).sub(u), new Vector3d(c).sub(r).sub(u), shadowSurface.texture(), p.alpha() * 0.9f, 0f));
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

    private OrbitLayout.Placement frontPlacement() {
        if (layout == null) return null;
        for (OrbitLayout.Placement p : layout.placements()) if (p.active()) return p;
        return null;
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
            draggingRing = true;
            dragLastX = click.x();
            dragStartX = click.x();
            ringMoved = false;
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
            // a held click wobbles a few pixels; the ring only turns once the mouse really travels
            if (!ringMoved && Math.abs(click.x() - dragStartX) < 6) return true;
            ringMoved = true;
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
        OrbitLayout.Placement hit = pick(x, y);
        boolean overFront = hit != null && hit.active() && !overview();
        // the panel under the cursor always gets the wheel, so a modifier stuck down can't hijack scrolling
        if (hasControlDown() && !overFront) {
            setOverview(scrollY < 0);
            return true;
        }
        if (!overview() && (overFront || !hasShiftDown())) {
            if (overFront) {
                float[] local = intersect(hit, rayDirection(x, y));
                if (local != null && view.scrolled(local[0], local[1], scrollX, scrollY)) return true;
            }
            // over the front panel's header, or anywhere with a page open, the wheel scrolls that panel; spinning
            // the ring would switch category and close the page under you
            if (overFront || view.orbitModuleOpen()) {
                OrbitLayout.Placement front = frontPlacement();
                if (front != null) view.scrolled(front.designW() / 2f, front.designH() * 0.6f, scrollX, scrollY);
                return true;
            }
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
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !hasControlDown()) {
            searchBar.open("");
            return true;
        }
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
        } else if (typed.equals("/") && plotScreen == null) {
            searchBar.open("");
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
        actors.close();
        shadowSurface.close();
        settingPreview.close();
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

    // command stands in for control on macs only, like vanilla; elsewhere super belongs to the desktop and its
    // release can go to the compositor, leaving glfw thinking it is held and every wheel turn a zoom out
    private static boolean hasControlDown() {
        boolean mac = net.minecraft.util.Util.getPlatform() == net.minecraft.util.Util.OS.OSX;
        return mac ? down(GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER)
                : down(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
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
