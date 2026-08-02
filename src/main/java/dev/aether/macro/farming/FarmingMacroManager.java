package dev.aether.macro.farming;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import dev.aether.modules.session.RestartManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of the currently active {@link AbstractFarmingMacro}.
 *
 * <p>Call {@link #enable(Minecraft, AbstractFarmingMacro)} to start a macro and
 * {@link #disable(Minecraft)} to stop it.  {@link #tick(Minecraft)} must be
 * wired to a {@code ClientTickEvents.END_CLIENT_TICK} handler in
 * {@link dev.aether.AetherClient}.
 */
public final class FarmingMacroManager {

    private FarmingMacroManager() {}

    private static final long START_GUI_CLOSE_TIMEOUT_MS = 3500L;
    private static final long START_GUI_CLOSE_POLL_MS = 50L;
    private static final long START_IN_WORLD_STABLE_MS = 300L;
    private static AbstractFarmingMacro activeMacro = null;
    private static volatile boolean deferredStartPending = false;

    /** Persists the active step within a macro's declared state cycle. */
    private static volatile Integer cachedCycleStep = null;

    public static void loadCycleStep() {
        if (cachedCycleStep == null) {
            try {
                java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                        .resolve("aether_last_direction.txt");
                if (java.nio.file.Files.exists(path)) {
                    cachedCycleStep = Integer.parseInt(java.nio.file.Files.readString(path).trim());
                }
            } catch (Exception ignored) {}
        }
    }

    public static void saveCycleStep(int step) {
        cachedCycleStep = Math.max(0, step);
        try {
            java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("aether_last_direction.txt");
            java.nio.file.Files.writeString(path, Integer.toString(cachedCycleStep));
        } catch (Exception ignored) {}
    }

    public static Integer getCachedCycleStep() {
        return cachedCycleStep;
    }

	/**
	 * Restores the active macro's cached farming orientation.
	 */
	public static boolean restoreConfiguredOrientation(Minecraft mc) {
		return activeMacro != null && activeMacro.restoreConfiguredOrientation(mc);
	}

    // -- Public API ------------------------------------------------------------

    /**
     * Instantiates a macro instance based on the current {@link AetherConfig#FARM_TYPE}.
     */
    public static AbstractFarmingMacro createMacroFromConfig() {
        String typeName = AetherConfig.FARM_TYPE.get();
        return switch (typeName) {
            case "A_D_FARM" -> new ADFarmMacro();
            case "COCOA_BEANS" -> new CocoaBeansMacro();
            case "SDS_MUSHROOM" -> new SDSMushroomMacro();
            case "W_S_FARM" -> new WSFarmMacro();
            // Legacy configuration value retained so existing profiles keep using W/S.
            case "W_S_CROP" -> new WSFarmMacro();
            case "CUSTOM" -> new CustomFarmMacro();
            case "S_SHAPE" -> new SShapeCropMacro();
            case "S_SHAPE_SUGAR_CANE" -> new SShapeSugarCaneMacro();
            default -> new SShapeCropMacro();
        };
    }

    /**
     * Enable the given macro, replacing any previously active one.
     * Always call this on the main client thread.
     */
    public static void enable(Minecraft mc, AbstractFarmingMacro macro) {
        if (RestartManager.isRestartSequenceActive()) {
            return;
        }
        if (AutoPestExchangeManager.shouldBlockFarmingResume()) {
            ClientUtils.sendDebugMessage("Farming start deferred because pest exchange has priority.");
            return;
        }

        if (activeMacro != null) {
            activeMacro.onDisable(mc);
            activeMacro = null;
        }

        if (SqueakyMousematManager.shouldUseBeforeFarming(mc)) {
            if (AutoPestExchangeManager.shouldBlockFarmingResume()) {
                ClientUtils.sendDebugMessage("Mousemat start skipped because pest exchange has priority.");
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

    private static void startMacroNow(Minecraft mc, AbstractFarmingMacro macro) {
        if (hasBlockingScreenOrContainer(mc)) {
            deferStartUntilReady(mc, macro);
            return;
        }

        ClientUtils.forceReleaseKeys();
        if (!GearManager.swapToFarmingToolSync(mc)) {
            ClientUtils.sendDebugMessage("Farming start: no farming tool found in hotbar, continuing with current item.");
        }

        activeMacro = macro;
        activeMacro.onEnable(mc);
    }

    private static void deferStartUntilReady(Minecraft mc, AbstractFarmingMacro macro) {
        if (deferredStartPending) {
            return;
        }

        deferredStartPending = true;
        ClientUtils.sendDebugMessage("Farming start deferred until open GUI/container closes.");
        MacroWorkerThread.getInstance().submit("FarmingStart-WaitForGuiClose", () -> {
            if (waitForFarmingResumeReady(mc, START_GUI_CLOSE_TIMEOUT_MS)) {
                deferredStartPending = false;
                mc.execute(() -> startMacroNow(mc, macro));
            } else if (!MacroWorkerThread.shouldAbortTask(mc)) {
                deferredStartPending = false;
                ClientUtils.sendDebugMessage("Farming start aborted: GUI/container did not fully close before resume timeout.");
            } else {
                deferredStartPending = false;
            }
        });
    }

    public static boolean waitForFarmingResumeReady(Minecraft mc, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long readySince = -1L;

        while (System.currentTimeMillis() < deadline && !MacroWorkerThread.shouldAbortTask(mc)) {
            if (isReadyForFarmingInput(mc)) {
                if (readySince < 0L) {
                    readySince = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - readySince >= START_IN_WORLD_STABLE_MS) {
                    return true;
                }
            } else {
                readySince = -1L;
            }

            MacroWorkerThread.sleep(START_GUI_CLOSE_POLL_MS);
        }

        return false;
    }

    private static boolean isReadyForFarmingInput(Minecraft mc) {
        return !hasBlockingScreenOrContainer(mc) && LoadoutManager.loadoutCleanupTicks <= 0;
    }

    private static boolean hasBlockingScreenOrContainer(Minecraft mc) {
        if (mc == null || mc.player == null) {
            return true;
        }

        if (!mc.isSameThread()) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            mc.execute(() -> future.complete(hasBlockingScreenOrContainer(mc)));
            try {
                return future.get(750, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                return true;
            }
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
    public static AbstractFarmingMacro getActiveMacro() {
        return activeMacro;
    }

    public static boolean isActive() {
        return activeMacro != null;
    }

    /** Releases input owned by the active farm macro without disabling it. */
    public static void releaseInputs(Minecraft mc) {
        if (activeMacro != null && mc != null && mc.options != null) {
            activeMacro.releaseAll(mc);
        }
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
