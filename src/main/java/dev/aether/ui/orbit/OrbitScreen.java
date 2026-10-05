package dev.aether.ui.orbit;

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
    private double mouseX = -1;
    private double mouseY = -1;

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
        overlay = new OrbitOverlay(this, host);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            yaw0 = player.getYRot();
            pitch0 = player.getXRot();
        }
        if (!categories.isEmpty()) view.orbitFocus(categories.get(0));
    }

    // -- simulation --------------------------------------------------------------------------------------------

    private void simulate() {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.05f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        clock += dt;
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

        if (state == State.OPENING) {
            if (hold > 0) hold -= dt;
            else openT += dt / 1.55f;
            for (int i = 0; i < count; i++) {
                double ao = Math.abs(wrap(i - ring.t));
                if (openT > 0.32f + ao * 0.1f) unfold[i].t = 1f;
            }
            if (openT >= 1f) {
                openT = 1f;
                state = State.OPEN;
            }
        } else if (state == State.CLOSING) {
            closeT += dt / 0.8f;
            for (OrbitSpring u : unfold) u.t = 0f;
            if (closeT >= 1f) {
                finishClose();
                return;
            }
        }
        String active = activeCategory();
        if (active != null) view.orbitFocus(active);
        computeLayout();
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
        layout = OrbitLayout.compute(new OrbitLayout.Input(new Vector3d(feet.x, feet.y, feet.z), yaw0, pitch0,
                player.getEyeHeight(), client.options.fov().get(), count, ring.x, zoom.x, expand.x, e,
                state == State.OPEN, clock, unfoldNow, activeIndex(), lp, ll, client.getWindow().getHeight()));
        OrbitLayout.Camera cam = layout.camera();
        OrbitCamera.set(cam.pos().x, cam.pos().y, cam.pos().z, cam.look().x, cam.look().y, cam.look().z, cam.fov());
    }

    // -- rendering ---------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float partialTick) {
        simulate();
        AetherRenderQueue.enqueue(this::renderOverlayFrame);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
    }

    // called from the level pass; draws every panel into its texture, then the panels into the world
    public static void renderWorldIfOpen() {
        if (Minecraft.getInstance().screen instanceof OrbitScreen screen) {
            try {
                screen.renderWorld();
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
                    surfaces[p.index()].texture(), p.alpha(), p.dim()));
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
            momentum = 0f;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
        mouseX = click.x();
        mouseY = click.y();
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
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (view.orbitOverlayOpen() && view.keyPressed(input)) return true;
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
        if (!event.isAllowedChatCharacter()) return true;
        view.charTyped(Character.toString(event.codepoint()));
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
