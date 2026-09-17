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

// the edit screen calls renderEditMode inside its own frame, so the gameplay callback skips rendering while it is open
public class HudRegistry {
    private static final float FADE_EPSILON = 0.01f;

    public static final List<HudElement> ELEMENTS = new ArrayList<>();
    private static float hudAlpha = 1f;

    public static MacroHudElement macroHud;
    public static ProfitHudElement sessionHud;
    public static ProfitHudElement lifetimeHud;
    public static ProfitHudElement dailyHud;
    public static TaskGroupHudElement intermediariesHud;
    public static TaskGroupHudElement midFarmingHud;
    public static TaskGroupHudElement failsafesHud;
    public static InventoryHudElement inventoryHud;
    public static WatermarkHudElement watermarkHud;
    public static MainStatusHudElement mainStatusHud;
    public static ScoreboardHudElement scoreboardHud;

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
        scoreboardHud = new ScoreboardHudElement();
        ELEMENTS.add(macroHud);
        ELEMENTS.add(sessionHud);
        ELEMENTS.add(lifetimeHud);
        ELEMENTS.add(dailyHud);
        ELEMENTS.add(intermediariesHud);
        ELEMENTS.add(midFarmingHud);
        ELEMENTS.add(failsafesHud);
        ELEMENTS.add(inventoryHud);
        ELEMENTS.add(new PestTargetHudElement());
        ELEMENTS.add(watermarkHud);
        ELEMENTS.add(mainStatusHud);
        ELEMENTS.add(scoreboardHud);

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

            // Keep themed inventory surfaces below the native item and player-model pass.
            if (alpha > FADE_EPSILON) {
                guiGraphics.nextStratum();
                renderMcElements(guiGraphics);
                if (ELEMENTS.stream().anyMatch(e -> e.rendersBeforeMinecraft() && e.isVisible())) {
                    AetherRenderQueue.enqueueBeforeGui(() -> renderBackgroundFrame(sw, sh, alpha));
                }
            }
            AetherRenderQueue.enqueue(() -> renderGameplayFrame(sw, sh, alpha));
        });
    }

    private static void renderMcElements(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
        for (HudElement e : ELEMENTS) {
            if (e.rendersWithHud() && e.isVisible()) {
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

    // must be called inside an already-open nvg frame
    public static void renderEditMode(NVGRenderer nvg) {
                if (StreamerModeManager.isEnabled()) {
            return;
        }

        for (HudElement e : ELEMENTS) {
            if (!e.isEnabled()) continue;
            e.render(nvg, true);
        }
    }

    private static boolean hasVisibleElements() {
        return ELEMENTS.stream().anyMatch(e -> e.rendersWithHud() && e.isVisible());
    }

    static boolean canRenderInGameplay(Minecraft mc) {
        if (AetherConfig.HUD_ONLY_WHILE_MACRO_RUNNING.get() && !MacroStateManager.isMacroRunning()) {
            return false;
        }
        return !AetherConfig.GUI_ONLY_IN_GARDEN.get()
                || ClientUtils.isSupportedHudArea();
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
            if (e.rendersWithHud() && !e.rendersBeforeMinecraft()) e.render(nvg, false);
        }
        nvg.restore();
    }

    // -- Queued render pass ----------------------------------------------------

    private static void renderBackgroundFrame(float width, float height, float alpha) {
        if (StreamerModeManager.isEnabled() || NanoVGManager.isDrawing()) return;
        if (!NanoVGManager.isInitialized()) NanoVGManager.init();
        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            nvg.globalAlpha(alpha);
            for (HudElement element : ELEMENTS) {
                if (element.rendersBeforeMinecraft()) element.render(nvg, false);
            }
        } finally {
            NanoVGManager.endFrame();
        }
    }

    public static void onGuiGraphicsClosed() {
        // Kept for the bootstrap hook ABI. Gameplay HUD drawing is queued from
        // the Fabric HUD extraction callback and flushed from GameRenderer.render.
    }

    private static void renderGameplayFrame(float width, float height, float alpha) {
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
            NotificationRenderer.render(nvg, width, height);
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
            if (!e.rendersWithHud() || !e.isVisible()) continue;
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
        scoreboardHud = null;
        hudAlpha = 0f;
    }
}
