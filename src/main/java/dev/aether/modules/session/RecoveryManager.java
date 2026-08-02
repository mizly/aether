package dev.aether.modules.session;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class RecoveryManager {
    public enum RecoveryMode {
        WORLD_CHANGE,
        LIMBO
    }

    private enum WorldChangeRecoveryPhase {
        IDLE,
        COMMAND_SEQUENCE,
        WAITING_FOR_GARDEN,
        WALK_PATH,
        PURE_ETHERWARP,
        WALK_ETHERWARP,
        ALIGNING,
        RESUME_DELAY
    }

    private enum RecoveryCommandStage {
        LOBBY("/lobby"),
        SKYBLOCK("/skyblock"),
        GARDEN("/warp garden");

        private final String command;

        RecoveryCommandStage(String command) {
            this.command = command;
        }
    }

    private static final long RECOVERY_COMMAND_DELAY_MS = 10_000L;
    private static final long WORLD_CHANGE_ALIGN_TIMEOUT_MS = 1200L;
    private static final long WORLD_CHANGE_RESUME_DELAY_MS = 1000L;
    private static int recoveryFailedAttempts = 0;
    private static long lastRecoveryActionTime = 0;
    private static RecoveryMode recoveryMode = null;
    private static long lastRecoveryCommandTime = 0L;
    private static RecoveryCommandStage recoveryCommandStage = null;
    private static boolean recoveryCommandSent = false;
    private static boolean recoveryCommandFailed = false;
    private static boolean recoveryComplete = false;
    private static WorldChangeRecoveryPhase worldChangePhase = WorldChangeRecoveryPhase.IDLE;
    private static Vec3 worldChangeTargetPosition = null;
    private static long worldChangeWaitUntilMs = 0L;
    private static boolean worldChangeUsedWalkAssist = false;
    private static boolean worldChangeAlignClicked = false;

    public static void reset() {
        recoveryFailedAttempts = 0;
        lastRecoveryActionTime = 0;
        recoveryMode = null;
        lastRecoveryCommandTime = 0L;
        recoveryCommandStage = null;
        recoveryCommandSent = false;
        recoveryCommandFailed = false;
        recoveryComplete = false;
        worldChangePhase = WorldChangeRecoveryPhase.IDLE;
        worldChangeTargetPosition = null;
        worldChangeWaitUntilMs = 0L;
        worldChangeUsedWalkAssist = false;
        worldChangeAlignClicked = false;
    }

    public static void beginRecovery() {
        reset();
        beginCommandSequence(RecoveryCommandStage.SKYBLOCK);
        ClientUtils.sendMessage("\u00A7eRecovery: waiting 10 seconds before /skyblock...", false);
    }

    public static void beginLimboRecovery() {
        reset();
        recoveryMode = RecoveryMode.LIMBO;
        beginCommandSequence(RecoveryCommandStage.LOBBY);
        ClientUtils.sendMessage("\u00A7eLimbo recovery: waiting 10 seconds before /lobby...", false);
    }

    public static void beginWorldChangeRecovery(Vec3 savedPosition) {
        reset();
        recoveryMode = RecoveryMode.WORLD_CHANGE;
        worldChangePhase = WorldChangeRecoveryPhase.COMMAND_SEQUENCE;
        worldChangeTargetPosition = savedPosition;
        beginCommandSequence(RecoveryCommandStage.LOBBY);
        worldChangeUsedWalkAssist = false;
        worldChangeAlignClicked = false;
    }

    public static boolean isWorldChangeRecoveryActive() {
        return recoveryMode == RecoveryMode.WORLD_CHANGE
                && worldChangePhase != WorldChangeRecoveryPhase.IDLE
                && worldChangeTargetPosition != null;
    }

    public static void handleRecoveryCommandFailure(String lowerText) {
        if (recoveryCommandStage == null || !recoveryCommandSent) {
            return;
        }

        long elapsedMs = System.currentTimeMillis() - lastRecoveryCommandTime;
        if (elapsedMs >= RECOVERY_COMMAND_DELAY_MS) {
            return;
        }

        if (!lowerText.contains("you are sending commands too fast!")
                && !lowerText.contains("cannot join skyblock")) {
            return;
        }

        recoveryCommandFailed = true;
        ClientUtils.sendDebugMessage("Recovery: " + recoveryCommandStage.command
                + " failed; retrying after the command delay.");
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (recoveryMode == RecoveryMode.WORLD_CHANGE) {
            updateWorldChangeRecovery(client);
            return;
        }

        if (MacroStateManager.getCurrentState() != MacroState.State.RECOVERING
                || client.screen instanceof PauseScreen || recoveryComplete) {
            return;
        }

        updateCommandSequence(client, false);
    }

    private static void beginCommandSequence(RecoveryCommandStage firstStage) {
        recoveryCommandStage = firstStage;
        recoveryCommandSent = false;
        recoveryCommandFailed = false;
        lastRecoveryCommandTime = System.currentTimeMillis();
    }

    private static void updateCommandSequence(Minecraft client, boolean worldChangeRecovery) {
        if (recoveryCommandStage == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastRecoveryCommandTime < RECOVERY_COMMAND_DELAY_MS) {
            return;
        }

        if (!recoveryCommandSent || recoveryCommandFailed) {
            sendRecoveryCommand(now);
            return;
        }

        switch (recoveryCommandStage) {
            case LOBBY -> {
                recoveryCommandStage = RecoveryCommandStage.SKYBLOCK;
                sendRecoveryCommand(now);
            }
            case SKYBLOCK -> {
                recoveryCommandStage = RecoveryCommandStage.GARDEN;
                sendRecoveryCommand(now);
            }
            case GARDEN -> finishCommandSequence(client, worldChangeRecovery, now);
        }
    }

    private static void sendRecoveryCommand(long now) {
        recoveryCommandSent = true;
        recoveryCommandFailed = false;
        lastRecoveryCommandTime = now;
        ClientUtils.sendMessage("\u00A7eRecovery: running " + recoveryCommandStage.command
                + ", waiting 10 seconds for a response...", false);
        ClientUtils.sendCommand(recoveryCommandStage.command);
    }

    private static void finishCommandSequence(Minecraft client, boolean worldChangeRecovery, long now) {
        recoveryCommandStage = null;
        recoveryCommandSent = false;
        recoveryCommandFailed = false;
        if (worldChangeRecovery) {
            worldChangePhase = WorldChangeRecoveryPhase.WAITING_FOR_GARDEN;
            lastRecoveryActionTime = now;
            return;
        }

        completeRecovery(client);
    }

    private static void completeRecovery(Minecraft client) {
        recoveryComplete = true;
        recoveryMode = null;
        ClientUtils.sendMessage("\u00A7aRecovery successful. Resuming farming...", false);
        recoveryFailedAttempts = 0;
        ClientUtils.sendDebugMessage("Starting farming macro after fixed recovery sequence");
        DynamicRestManager.scheduleNextRest();
        client.execute(() -> {
            if (MacroStateManager.getCurrentState() != MacroState.State.RECOVERING) {
                return;
            }

            FailsafeManager.syncSelectedSlotFromClient(client);
            GearManager.swapToFarmingTool(client);
            FailsafeManager.syncSelectedSlotFromClient(client);
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            SqueakyMousematManager.armReapplyAttempt();
            FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig());
            FailsafeManager.syncSelectedSlotFromClient(client);
        });
    }
    private static void updateWorldChangeRecovery(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }

        if (worldChangeTargetPosition == null) {
            ClientUtils.sendMessage("\u00A7cWorld change recovery failed: no saved position.", false);
            MacroStateManager.stopMacro(client, "World change recovery failed: no saved position", false);
            return;
        }

        if (worldChangePhase == WorldChangeRecoveryPhase.COMMAND_SEQUENCE) {
            updateCommandSequence(client, true);
            return;
        }

        if (worldChangePhase == WorldChangeRecoveryPhase.WAITING_FOR_GARDEN) {
            MacroState.Location location = ClientUtils.getCurrentLocation();
            if (location == MacroState.Location.GARDEN) {
                startWorldChangePrimaryRecovery(client);
                return;
            }

            return;
        }

        if (worldChangePhase == WorldChangeRecoveryPhase.ALIGNING && !RotationManager.isRotating()) {
            if (!worldChangeAlignClicked) {
                worldChangeAlignClicked = true;
                GearManager.swapToAOTVSync(client);
                ClientUtils.performUseClick();
                lastRecoveryActionTime = System.currentTimeMillis();
                return;
            }

            if (System.currentTimeMillis() - lastRecoveryActionTime >= WORLD_CHANGE_ALIGN_TIMEOUT_MS) {
                finishWorldChangeRecovery(client);
            }
            return;
        }

        if (worldChangePhase == WorldChangeRecoveryPhase.RESUME_DELAY) {
            if (System.currentTimeMillis() < worldChangeWaitUntilMs) {
                return;
            }
            completeWorldChangeRecovery(client);
        }
    }

    private static void startWorldChangeWalkAssist(Minecraft client) {
        if (recoveryMode != RecoveryMode.WORLD_CHANGE
                || worldChangeTargetPosition == null) {
            return;
        }

        worldChangePhase = WorldChangeRecoveryPhase.WALK_ETHERWARP;
        worldChangeUsedWalkAssist = true;
        ClientUtils.sendMessage("\u00A7eWorld change recovery: pure etherwarp failed, trying walk-assisted etherwarp...",
                false);
        PathfindingManager.startConfiguredEtherwarp(
                client,
                Mth.floor(worldChangeTargetPosition.x),
                Mth.floor(worldChangeTargetPosition.y),
                Mth.floor(worldChangeTargetPosition.z),
                () -> client.execute(() -> finishWorldChangeNavigation(client, true)),
                () -> client.execute(() -> {
                    ClientUtils.sendMessage("\u00A7cWorld change recovery failed. Stopping farming.", false);
                    MacroStateManager.stopMacro(client, "World change recovery path failed", false);
                }));
    }

    private static void startWorldChangePrimaryRecovery(Minecraft client) {
        if (client == null || client.player == null || worldChangeTargetPosition == null) {
            return;
        }

        int currentY = Mth.floor(client.player.getY());
        int targetY = Mth.floor(worldChangeTargetPosition.y);
        if (targetY > currentY) {
            ClientUtils.sendDebugMessage("World change recovery: target is above current position, prioritizing etherwarp");
            startWorldChangeEtherwarpRecovery(client);
            return;
        }

        ClientUtils.sendDebugMessage("World change recovery: target is level or below current position, prioritizing walk");
        startWorldChangeWalkRecovery(client);
    }

    private static void startWorldChangeWalkRecovery(Minecraft client) {
        if (recoveryMode != RecoveryMode.WORLD_CHANGE || worldChangeTargetPosition == null) {
            return;
        }

        worldChangePhase = WorldChangeRecoveryPhase.WALK_PATH;
        ClientUtils.sendMessage("\u00A7eWorld change recovery: walking back to the saved position...", false);
        PathfindingManager.startConfiguredWalk(
                client,
                Mth.floor(worldChangeTargetPosition.x),
                Mth.floor(worldChangeTargetPosition.y),
                Mth.floor(worldChangeTargetPosition.z),
                () -> client.execute(() -> finishWorldChangeNavigation(client, true)),
                () -> client.execute(() -> startWorldChangeEtherwarpRecovery(client)),
                true,
                0.5,
                false,
                true);
    }

    private static void startWorldChangeEtherwarpRecovery(Minecraft client) {
        if (recoveryMode != RecoveryMode.WORLD_CHANGE || worldChangeTargetPosition == null) {
            return;
        }

        worldChangePhase = WorldChangeRecoveryPhase.PURE_ETHERWARP;
        worldChangeUsedWalkAssist = false;
        ClientUtils.sendMessage("\u00A7eWorld change recovery: walking failed, trying etherwarp...", false);
        PathfindingManager.startConfiguredPureEtherwarp(
                client,
                Mth.floor(worldChangeTargetPosition.x),
                Mth.floor(worldChangeTargetPosition.y),
                Mth.floor(worldChangeTargetPosition.z),
                () -> client.execute(() -> finishWorldChangeNavigation(client, false)),
                () -> client.execute(() -> startWorldChangeWalkAssist(client)));
    }

    private static void finishWorldChangeNavigation(Minecraft client, boolean alignAfterNavigation) {
        if (recoveryMode != RecoveryMode.WORLD_CHANGE) {
            return;
        }

        if (alignAfterNavigation || worldChangeUsedWalkAssist) {
            performWorldChangeAotvAlign(client);
            return;
        }

        finishWorldChangeRecovery(client);
    }

    private static void performWorldChangeAotvAlign(Minecraft client) {
        if (client == null || client.player == null || worldChangeTargetPosition == null) {
            finishWorldChangeRecovery(client);
            return;
        }

        int slot = GearManager.findAspectOfTheVoidSlot(client);
        if (slot < 0 || slot > 8) {
            ClientUtils.sendDebugMessage("World change recovery align skipped: no AOTV/AOTE in hotbar");
            finishWorldChangeRecovery(client);
            return;
        }

        worldChangePhase = WorldChangeRecoveryPhase.ALIGNING;
        worldChangeAlignClicked = false;
        Vec3 target = new Vec3(
                Math.floor(worldChangeTargetPosition.x) + 0.5,
                Math.floor(worldChangeTargetPosition.y) - 0.5,
                Math.floor(worldChangeTargetPosition.z) + 0.5);
        RotationUtils.Rotation rotation = RotationUtils.calculateLookAt(client.player.getEyePosition(), target);
        RotationManager.cancelRotation();
        RotationManager.rotateToYawPitch(client, rotation.yaw, rotation.pitch, 120L);
    }

    private static void finishWorldChangeRecovery(Minecraft client) {
        if (client == null) {
            return;
        }

        worldChangePhase = WorldChangeRecoveryPhase.RESUME_DELAY;
        worldChangeWaitUntilMs = System.currentTimeMillis() + WORLD_CHANGE_RESUME_DELAY_MS;
    }

    private static void completeWorldChangeRecovery(Minecraft client) {
        recoveryFailedAttempts = 0;
        recoveryMode = null;
        worldChangePhase = WorldChangeRecoveryPhase.IDLE;
        worldChangeTargetPosition = null;
        worldChangeWaitUntilMs = 0L;
        worldChangeUsedWalkAssist = false;
        worldChangeAlignClicked = false;
        DynamicRestManager.scheduleNextRest();
        ClientUtils.sendMessage("\u00A7aWorld change recovery complete. Resuming farming...", false);
        client.execute(() -> {
            if (MacroStateManager.getCurrentState() != MacroState.State.RECOVERING) {
                return;
            }

            FailsafeManager.syncSelectedSlotFromClient(client);
            GearManager.swapToFarmingTool(client);
            FailsafeManager.syncSelectedSlotFromClient(client);
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            SqueakyMousematManager.armReapplyAttempt();
            FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig());
            FailsafeManager.syncSelectedSlotFromClient(client);
        });
    }

}
