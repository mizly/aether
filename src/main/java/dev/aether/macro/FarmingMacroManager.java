package dev.aether.macro;

import dev.aether.config.AetherConfig;
import dev.aether.macro.impl.ADFarmMacro;
import dev.aether.macro.impl.CocoaBeansMacro;
import dev.aether.macro.impl.SDSMushroomMacro;
import dev.aether.macro.impl.SShapeCropMacro;
import dev.aether.macro.impl.SShapeSugarCaneMacro;
import dev.aether.macro.impl.WSCropMacro;
import dev.aether.macro.impl.WSFarmMacro;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import dev.aether.modules.session.RestartManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

/**
 * Manages the lifecycle of the currently active {@link AbstractMacro}.
 *
 * <p>Call {@link #enable(Minecraft, AbstractMacro)} to start a macro and
 * {@link #disable(Minecraft)} to stop it.  {@link #tick(Minecraft)} must be
 * wired to a {@code ClientTickEvents.END_CLIENT_TICK} handler in
 * {@link dev.aether.AetherClient}.
 */
public final class FarmingMacroManager {

    private FarmingMacroManager() {}

    private static final long START_GUI_CLOSE_TIMEOUT_MS = 3500L;
    private static final long START_GUI_CLOSE_POLL_MS = 50L;
    private static AbstractMacro activeMacro = null;
    private static volatile boolean deferredStartPending = false;

    /**
     * Persists the last confirmed row direction across macro restarts so we
     * resume in the same direction after a pest-clean / wardrobe cycle.
     */
    private static volatile AbstractMacro.State cachedRowDirection = null;

    public static void loadDirection() {
        if (cachedRowDirection == null) {
            try {
                java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aether_last_direction.txt");
                if (java.nio.file.Files.exists(path)) {
                    String val = java.nio.file.Files.readString(path).trim();
                    switch (val) {
                        case "1": cachedRowDirection = AbstractMacro.State.RIGHT; break;
                        case "0": cachedRowDirection = AbstractMacro.State.LEFT; break;
                        case "2": cachedRowDirection = AbstractMacro.State.FORWARD; break;
                        case "3": cachedRowDirection = AbstractMacro.State.BACKWARD; break;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public static void saveDirection(AbstractMacro.State state) {
        if (state == AbstractMacro.State.LEFT || state == AbstractMacro.State.RIGHT ||
            state == AbstractMacro.State.FORWARD || state == AbstractMacro.State.BACKWARD) {
            cachedRowDirection = state;
            try {
                java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aether_last_direction.txt");
                String val = "0";
                if (state == AbstractMacro.State.RIGHT) val = "1";
                else if (state == AbstractMacro.State.FORWARD) val = "2";
                else if (state == AbstractMacro.State.BACKWARD) val = "3";
                java.nio.file.Files.writeString(path, val);
            } catch (Exception ignored) {}
        }
    }

    public static AbstractMacro.State getCachedDirection() {
        return cachedRowDirection;
    }

    // -- Public API ------------------------------------------------------------

    /**
     * Instantiates a macro instance based on the current {@link AetherConfig#FARM_TYPE}.
     */
    public static AbstractMacro createMacroFromConfig() {
        String typeName = AetherConfig.FARM_TYPE.get();
        return switch (typeName) {
            case "A_D_FARM" -> new ADFarmMacro();
            case "COCOA_BEANS" -> new CocoaBeansMacro();
            case "SDS_MUSHROOM" -> new SDSMushroomMacro();
            case "W_S_FARM" -> new WSFarmMacro();
            case "W_S_CROP" -> new WSCropMacro();
            case "S_SHAPE" -> new SShapeCropMacro();
            case "S_SHAPE_SUGAR_CANE" -> new SShapeSugarCaneMacro();
            default -> new SShapeCropMacro();
        };
    }

    /**
     * Enable the given macro, replacing any previously active one.
     * Always call this on the main client thread.
     */
    public static void enable(Minecraft mc, AbstractMacro macro) {
        if (RestartManager.isRestartSequenceActive()) {
            return;
        }
        if (AutoPestExchangeManager.shouldBlockFarmingResume()) {
            ClientUtils.sendDebugMessage(mc, "Farming start deferred because pest exchange has priority.");
            return;
        }

        if (activeMacro != null) {
            activeMacro.onDisable(mc);
            activeMacro = null;
        }

        if (SqueakyMousematManager.shouldUseBeforeFarming(mc)) {
            if (AutoPestExchangeManager.shouldBlockFarmingResume()) {
                ClientUtils.sendDebugMessage(mc, "Mousemat start skipped because pest exchange has priority.");
                return;
            }
            MacroWorkerThread.getInstance().submit("FarmingStartMousemat", () -> {
                if (MacroWorkerThread.shouldAbortTask(mc, MacroState.State.FARMING)
                        || AutoPestExchangeManager.shouldBlockFarmingResume()) {
                    return;
                }

                SqueakyMousematManager.useIfNeeded(mc);
                if (MacroWorkerThread.shouldAbortTask(mc, MacroState.State.FARMING)
                        || AutoPestExchangeManager.shouldBlockFarmingResume()) {
                    return;
                }

                mc.execute(() -> startMacroNow(mc, macro));
            });
            return;
        }

        startMacroNow(mc, macro);
    }

    private static void startMacroNow(Minecraft mc, AbstractMacro macro) {
        if (hasBlockingScreenOrContainer(mc)) {
            deferStartUntilReady(mc, macro);
            return;
        }

        ClientUtils.forceReleaseKeys(mc);
        if (!GearManager.swapToFarmingToolSync(mc)) {
            ClientUtils.sendDebugMessage(mc, "Farming start: no farming tool found in hotbar, continuing with current item.");
        }

        activeMacro = macro;
        activeMacro.onEnable(mc);
    }

    private static void deferStartUntilReady(Minecraft mc, AbstractMacro macro) {
        if (deferredStartPending) {
            return;
        }

        deferredStartPending = true;
        ClientUtils.sendDebugMessage(mc, "Farming start deferred until open GUI/container closes.");
        MacroWorkerThread.getInstance().submit("FarmingStart-WaitForGuiClose", () -> {
            long deadline = System.currentTimeMillis() + START_GUI_CLOSE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && !MacroWorkerThread.shouldAbortTask(mc)) {
                if (!hasBlockingScreenOrContainer(mc)) {
                    deferredStartPending = false;
                    mc.execute(() -> startMacroNow(mc, macro));
                    return;
                }
                MacroWorkerThread.sleep(START_GUI_CLOSE_POLL_MS);
            }

            deferredStartPending = false;
            if (!MacroWorkerThread.shouldAbortTask(mc)) {
                ClientUtils.sendDebugMessage(mc,
                        "Farming start aborted: GUI/container did not close before resume timeout.");
            }
        });
    }

    private static boolean hasBlockingScreenOrContainer(Minecraft mc) {
        if (mc == null || mc.player == null) {
            return true;
        }

        if (mc.screen != null) {
            return true;
        }

        return mc.player.containerMenu != null
                && mc.player.inventoryMenu != null
                && mc.player.containerMenu.containerId != mc.player.inventoryMenu.containerId;
    }

    /**
     * Disable the currently active macro (if any).
     * Always call this on the main client thread.
     */
    public static void disable(Minecraft mc) {
        if (activeMacro != null) {
            activeMacro.onDisable(mc);
            activeMacro = null;
        }
    }

    /** Returns the currently active macro, or {@code null} if none. */
    public static AbstractMacro getActiveMacro() {
        return activeMacro;
    }

    public static boolean isActive() {
        return activeMacro != null;
    }

    /**
     * Advance the active macro by one tick.
     * Wire this to {@code ClientTickEvents.END_CLIENT_TICK}.
     */
    public static void tick(Minecraft mc) {
        if (activeMacro != null && mc.player != null) {
            activeMacro.onTick(mc);
        }
    }
}
