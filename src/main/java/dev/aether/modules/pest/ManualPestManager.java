package dev.aether.modules.pest;

import dev.aether.bootstrap.AetherKeybindHandler;
import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.DesktopNotificationManager;
import dev.aether.modules.failsafe.FailsafeColourFlashManager;
import dev.aether.modules.failsafe.FailsafeSoundManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.helpers.PestCompletionGuard;
import dev.aether.modules.pest.helpers.PestDiscoDestinationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import dev.aether.util.WindowFocusHelper;
import net.minecraft.client.Minecraft;

/**
 * Manual pest mode replaces the automated Pest Destroyer action with a pause
 * and alert. PestManager decides when the normal destroyer trigger is ready;
 * this class only handles the manual pause/resume lifecycle.
 */
public final class ManualPestManager {

    private enum Phase { IDLE, PREPARING, WAITING, RESUMING }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile int pausedFromLoadout = -1;
    private static volatile long waitingStartedAt = 0L;
    private static volatile int clearedPestTabTicks = 0;

    private ManualPestManager() {
    }

    public static void reset() {
        phase = Phase.IDLE;
        pausedFromLoadout = -1;
        waitingStartedAt = 0L;
        clearedPestTabTicks = 0;
        PestManager.clearCleaningTriggerPending();
    }

    public static boolean isActive() {
        return phase != Phase.IDLE;
    }

    public static boolean startFromPestDestroyerTrigger(Minecraft client, int count, String plot) {
        if (!AetherConfig.MANUAL_PEST_MODE.get() || phase != Phase.IDLE) {
            return false;
        }
        if (client == null || client.player == null || client.getConnection() == null) {
            return false;
        }
        if (!MacroStateManager.isMacroRunning()) {
            return false;
        }

        beginPause(client, Math.max(1, count), plot);
        return true;
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getConnection() == null) {
            return;
        }
        if (!AetherConfig.MANUAL_PEST_MODE.get()) {
            if (phase != Phase.IDLE) {
                reset();
            }
            return;
        }

        if (phase == Phase.WAITING) {
            tickWaiting(client);
        }
    }

    private static void tickWaiting(Minecraft client) {
        if (MacroStateManager.isMacroRunning()) {
            reset();
            return;
        }

        if (currentPestCount(client) <= 0) {
            if (!PestCompletionGuard.shouldAcceptFinishReading(waitingStartedAt, false)) {
                if (clearedPestTabTicks == 0) {
                    ClientUtils.sendDebugMessage("Manual pest: ignoring clear tab reading during startup grace.");
                }
                clearedPestTabTicks = 0;
                return;
            }

            clearedPestTabTicks++;
            if (PestCompletionGuard.isConfirmed(clearedPestTabTicks)) {
                beginResume(client);
            }
        } else {
            clearedPestTabTicks = 0;
        }
    }

    private static void beginPause(Minecraft client, int count, String plot) {
        int previousLoadout = LoadoutManager.trackedLoadoutSlot;
        ClientUtils.sendMessage("\u00A7eManual Pest Mode: \u00A7f" + count
                + " pest(s) detected. \u00A7ePausing for manual kill.", false);
        MacroStateManager.stopMacro(client, "Manual pest mode: pausing for manual pest kill", false);
        phase = Phase.PREPARING;
        pausedFromLoadout = previousLoadout;
        CommandUtils.initiateSetSpawn();
        MacroWorkerThread.getInstance().submit("ManualPest-Prep", () -> {
            plotTpBeforeVacuum(client, plot);
            swapToPestKillLoadout(client, previousLoadout);
            client.execute(() -> {
                if (phase == Phase.PREPARING && AetherConfig.MANUAL_PEST_MODE.get()) {
                    phase = Phase.WAITING;
                    waitingStartedAt = System.currentTimeMillis();
                    clearedPestTabTicks = 0;
                    fireAlert(count);
                }
            });
        });
    }

    private static void plotTpBeforeVacuum(Minecraft client, String plot) {
        if (!AetherConfig.MANUAL_PEST_TP_TO_PLOT.get()) {
            return;
        }
        if (!PestDiscoDestinationManager.isUsablePlot(plot)) {
            ClientUtils.sendDebugMessage("Manual pest: no usable infested plot (" + plot + "), skipping auto plottp.");
            return;
        }
        ClientUtils.sendMessage("\u00A7eManual Pest Mode: \u00A7fteleporting to infested plot "
                + plot + "\u00A7e, then equipping vacuum.", false);
        CommandUtils.plotTp(plot);
        MacroWorkerThread.sleep(200); // let the world load / position settle
    }

    private static void beginResume(Minecraft client) {
        phase = Phase.RESUMING;
        ClientUtils.sendMessage("\u00A7aManual Pest Mode: pests cleared. Warping to garden and restarting the macro.",
                false);
        MacroWorkerThread.getInstance().submit("ManualPest-Resume", () -> {
            try {
                CommandUtils.warpGarden();
                restoreFarmingLoadout(client);
            } finally {
                client.execute(() -> {
                    if (AetherConfig.MANUAL_PEST_MODE.get()) {
                        AetherKeybindHandler.startFarmingMacro(client, false);
                    }
                    PestManager.clearCleaningTriggerPending();
                    phase = Phase.IDLE;
                    waitingStartedAt = 0L;
                    clearedPestTabTicks = 0;
                });
            }
        });
    }

    private static void swapToPestKillLoadout(Minecraft client, int previousLoadout) {
        int targetSlot = AetherConfig.LOADOUT_SLOT_PEST_KILL.get();
        if (targetSlot <= 0) {
            return;
        }

        int effectivePrevious = previousLoadout > 0 ? previousLoadout : AetherConfig.LOADOUT_SLOT_FARMING.get();
        if (effectivePrevious == targetSlot) {
            ClientUtils.sendDebugMessage("Manual pest: already on kill loadout " + targetSlot + ", no swap needed.");
            return;
        }

        swapLoadout(client, targetSlot);
    }

    private static void restoreFarmingLoadout(Minecraft client) {
        int targetSlot = AetherConfig.LOADOUT_SLOT_FARMING.get();
        if (targetSlot <= 0) {
            return;
        }

        int current = LoadoutManager.trackedLoadoutSlot > 0 ? LoadoutManager.trackedLoadoutSlot : pausedFromLoadout;
        if (current <= 0 || current == targetSlot) {
            return;
        }

        swapLoadout(client, targetSlot);
    }

    private static void swapLoadout(Minecraft client, int targetSlot) {
        if (LoadoutManager.trackedLoadoutSlot == targetSlot) {
            return;
        }

        ClientUtils.sendDebugMessage("Manual pest: swapping to loadout slot " + targetSlot);
        client.execute(() -> GearManager.ensureLoadoutSlot(client, targetSlot));

        long startWait = System.currentTimeMillis();
        while (!LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - startWait < 2000) {
            MacroWorkerThread.sleep(25);
        }

        ClientUtils.waitForWardrobeGui();
        long finishWait = System.currentTimeMillis();
        while (LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - finishWait < 7000) {
            MacroWorkerThread.sleep(50);
        }
        if (LoadoutManager.isSwappingLoadout) {
            LoadoutManager.forceLoadoutCompletionFailsafe(client);
        }

        long cleanupWait = System.currentTimeMillis();
        while (LoadoutManager.loadoutCleanupTicks > 0 && System.currentTimeMillis() - cleanupWait < 2000) {
            MacroWorkerThread.sleep(50);
        }
        MacroWorkerThread.sleep(250);
    }

    private static void fireAlert(int count) {
        FailsafeColourFlashManager.trigger();
        FailsafeSoundManager.playConfiguredSoundFile(AetherConfig.MANUAL_PEST_SOUND_FILE.get());
        WindowFocusHelper.focusMinecraftWindow("ManualPestWindowFocus");
        if (AetherConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.get()) {
            DesktopNotificationManager.notify("Pests Detected",
                    count + " pest(s) spawned - macro paused for manual kill.", true);
        }
    }

    private static int currentPestCount(Minecraft client) {
        int rawTab = PestManager.getTabAliveCountNow(client);
        if (rawTab >= 0) {
            return rawTab;
        }
        return PestManager.getInfestedPlotsFromTab(client).isEmpty() ? 0 : 1;
    }
}
