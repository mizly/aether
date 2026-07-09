package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;
import dev.aether.util.AetherResources;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.notification.NotificationManager;
import dev.aether.notification.NotificationRenderer;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import dev.aether.ui.MainGUI;
import dev.aether.ui.theme.Theme;
import dev.aether.util.ClientUtils;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Central registry for all {@link HudElement}s.
 *
 * <p>Call {@link #register()} once during mod init to create all elements and
 * hook into Fabric's {@code HudElementRegistry}.</p>
 *
 * <p>The {@link HudEditScreen} calls {@link #renderEditMode(NVGRenderer)} within
 * its own NVG frame - the gameplay callback skips rendering when the edit screen
 * is open to prevent double-draws.</p>
 */
public class HudRegistry {
    private static final float FADE_EPSILON = 0.01f;

    public static final List<HudElement> ELEMENTS = new ArrayList<>();
    private static float hudAlpha = 1f;

    /** The singleton macro-status panel. */
    public static MacroHudElement macroHud;
    /** Session profit panel. */
    public static ProfitHudElement sessionHud;
    /** Lifetime profit panel. */
    public static ProfitHudElement lifetimeHud;
    /** Daily profit panel. */
    public static ProfitHudElement dailyHud;
    /** Intermediary task status panel. */
    public static TaskGroupHudElement intermediariesHud;
    /** Mid-farming task status panel. */
    public static TaskGroupHudElement midFarmingHud;
    /** Failsafe status panel. */
    public static TaskGroupHudElement failsafesHud;
    /** Inventory preview panel. */
    public static InventoryHudElement inventoryHud;
    /** Watermark panel. */
    public static WatermarkHudElement watermarkHud;
    /** Main status panel (Main theme). */
    public static MainStatusHudElement mainStatusHud;

    private HudRegistry() {}

    // -- Registration ----------------------------------------------------------

    public static void register() {
        macroHud    = new MacroHudElement();
        sessionHud  = new ProfitHudElement("session");
        lifetimeHud = new ProfitHudElement("lifetime");
        dailyHud    = new ProfitHudElement("daily");
        intermediariesHud = new TaskGroupHudElement(TaskGroupHudElement.Group.INTERMEDIARIES);
        midFarmingHud = new TaskGroupHudElement(TaskGroupHudElement.Group.MID_FARMING_TASKS);
        failsafesHud = new TaskGroupHudElement(TaskGroupHudElement.Group.FAILSAFES);
        inventoryHud = new InventoryHudElement();
        watermarkHud  = new WatermarkHudElement();
        mainStatusHud = new MainStatusHudElement();
        ELEMENTS.add(macroHud);
        ELEMENTS.add(sessionHud);
        ELEMENTS.add(lifetimeHud);
        ELEMENTS.add(dailyHud);
        ELEMENTS.add(intermediariesHud);
        ELEMENTS.add(midFarmingHud);
        ELEMENTS.add(failsafesHud);
        ELEMENTS.add(inventoryHud);
        ELEMENTS.add(watermarkHud);
        ELEMENTS.add(mainStatusHud);

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("aether", "hud"), (guiGraphics, delta) -> {
                        Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                hudAlpha = 0f;
                return;
            }

            // Let the edit screen render its own elements
            if (mc.screen instanceof HudEditScreen) return;

            // MainGUI renders HUD elements itself (Gui.render() is suppressed while it's open)
            if (mc.screen instanceof MainGUI) return;
            if (StreamerModeManager.isEnabled()) {
                hudAlpha = 0f;
                return;
            }

            boolean anyVisible = hasVisibleElements();
            boolean hasNotifications = NotificationManager.getCount() > 0;
            float alpha = tickHudAlpha(canRenderInGameplay(mc) && anyVisible);
            if (alpha <= FADE_EPSILON && !hasNotifications) return;

            var win = mc.getWindow();
            float sw = win.getGuiScaledWidth();
            float sh = win.getGuiScaledHeight();
            float frameDelta = delta.getGameTimeDeltaTicks();

            // Extract MC-rendered parts now; queued NVG draws after MC flushes GUI state.
            if (alpha > FADE_EPSILON) {
                renderMcElements(guiGraphics);
            }
            AetherRenderQueue.enqueue(() -> renderGameplayFrame(sw, sh, alpha, frameDelta));
        });
    }

    private static void renderMcElements(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
        for (HudElement e : ELEMENTS) {
            if (e.isVisible()) {
                e.renderMinecraft(graphics, false);
            }
        }
    }

    public static void renderConfigTransition(NVGRenderer nvg) {
                if (StreamerModeManager.isEnabled()) {
            hudAlpha = 0f;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            hudAlpha = 0f;
            return;
        }

        float alpha = tickHudAlpha(false);
        if (alpha <= FADE_EPSILON || !canRenderInGameplay(mc)) return;
        renderHudElements(nvg, alpha);
    }

    // -- Edit-mode helper ------------------------------------------------------

    /**
     * Renders all elements in edit mode.
     * Must be called <em>within</em> an already-open NVG frame.
     */
    public static void renderEditMode(NVGRenderer nvg) {
                if (StreamerModeManager.isEnabled()) {
            return;
        }

        for (HudElement e : ELEMENTS) {
            e.render(nvg, true);
        }
    }

    private static boolean hasVisibleElements() {
        return ELEMENTS.stream().anyMatch(HudElement::isVisible);
    }

    private static boolean canRenderInGameplay(Minecraft mc) {
        if (AetherConfig.HUD_ONLY_WHILE_MACRO_RUNNING.get() && !MacroStateManager.isMacroRunning()) {
            return false;
        }
        return !AetherConfig.GUI_ONLY_IN_GARDEN.get()
                || ClientUtils.isSupportedHudArea(mc);
    }

    private static float tickHudAlpha(boolean visible) {
        float target = visible ? 1f : 0f;
        float speed = Math.max(0.08f, Math.min(0.35f, Theme.animationFactor(0.625f)));
        hudAlpha += (target - hudAlpha) * speed;
        if (Math.abs(target - hudAlpha) < FADE_EPSILON) {
            hudAlpha = target;
        }
        return hudAlpha;
    }

    private static void renderHudElements(NVGRenderer nvg, float alpha) {
        if (alpha <= FADE_EPSILON) return;

        nvg.save();
        nvg.globalAlpha(alpha);
        for (HudElement e : ELEMENTS) {
            e.render(nvg, false);
        }
        nvg.restore();
    }

    // -- Queued render pass ----------------------------------------------------

    public static void onGuiGraphicsClosed() {
        // Kept for the bootstrap hook ABI. Gameplay HUD drawing is queued from
        // the Fabric HUD extraction callback and flushed from GameRenderer.render.
    }

    private static void renderGameplayFrame(float width, float height, float alpha, float deltaTicks) {
        if (StreamerModeManager.isEnabled()) {
            return;
        }
        if (NanoVGManager.isDrawing()) {
            return;
        }
        if (!NanoVGManager.isInitialized()) {
            NanoVGManager.init();
        }

        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            renderHudElements(nvg, alpha);
            NotificationRenderer.render(nvg, width, height, deltaTicks);
            if (alpha > FADE_EPSILON) {
                renderOverlayElements(nvg, alpha);
            }
        } finally {
            NanoVGManager.endFrame();
        }
    }

    private static void renderOverlayElements(NVGRenderer nvg, float alpha) {
        nvg.save();
        nvg.globalAlpha(alpha);
        for (HudElement e : ELEMENTS) {
            if (!e.isVisible()) continue;
            nvg.save();
            nvg.translate(e.getX(), e.getY());
            nvg.scale(e.getScale(), e.getScale());
            e.renderOverlay(nvg, false);
            nvg.restore();
        }
        nvg.restore();
    }

    public static void reset() {
        ELEMENTS.clear();
        macroHud = null;
        sessionHud = null;
        lifetimeHud = null;
        dailyHud = null;
        intermediariesHud = null;
        midFarmingHud = null;
        failsafesHud = null;
        inventoryHud = null;
        watermarkHud = null;
        mainStatusHud = null;
        hudAlpha = 0f;
    }
}

