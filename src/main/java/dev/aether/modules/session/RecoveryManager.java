package dev.aether.modules.session;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
        WALK_PATH,
        PURE_ETHERWARP,
        WALK_ETHERWARP,
        ALIGNING,
        RESUME_DELAY
    }

    private static final RecoverySequence commandSequence = new RecoverySequence();
    private static final long WORLD_CHANGE_ALIGN_TIMEOUT_MS = 1200L;
    private static final long WORLD_CHANGE_RESUME_DELAY_MS = 1000L;
    private static long lastRecoveryActionTime = 0;
    private static RecoveryMode recoveryMode = null;
    private static volatile boolean recoveryComplete = false;
    private static WorldChangeRecoveryPhase worldChangePhase = WorldChangeRecoveryPhase.IDLE;
    private static Vec3 worldChangeTargetPosition = null;
    private static long worldChangeWaitUntilMs = 0L;
    private static boolean worldChangeUsedWalkAssist = false;
    private static boolean worldChangeAlignClicked = false;
    private static ClientLevel navigationWorld;

    public static void reset() {
        commandSequence.reset(System.currentTimeMillis());
        lastRecoveryActionTime = 0;
        recoveryMode = null;
        recoveryComplete = false;
        worldChangePhase = WorldChangeRecoveryPhase.IDLE;
        worldChangeTargetPosition = null;
        worldChangeWaitUntilMs = 0L;
        worldChangeUsedWalkAssist = false;
        worldChangeAlignClicked = false;
        navigationWorld = null;
    }

    public static void beginRecovery() {
        prepareRecovery();
        ClientUtils.sendMessage("\u00A7e" + AetherLang.localize(
                "Recovery: waiting for SkyBlock and the Garden before resuming..."), false);
    }

    public static void beginLimboRecovery() {
        prepareRecovery();
        recoveryMode = RecoveryMode.LIMBO;
        ClientUtils.sendMessage("\u00A7e" + AetherLang.localize(
                "Limbo recovery: waiting for SkyBlock and the Garden before resuming..."), false);
    }

    public static void beginWorldChangeRecovery(Vec3 savedPosition) {
        prepareRecovery();
        recoveryMode = RecoveryMode.WORLD_CHANGE;
        worldChangePhase = WorldChangeRecoveryPhase.COMMAND_SEQUENCE;
        worldChangeTargetPosition = savedPosition;
    }

    private static void prepareRecovery() {
        MacroStateManager.stopMacro(Minecraft.getInstance(), "Preparing location-verified recovery", false);
        MacroStateManager.setCurrentState(MacroState.State.RECOVERING);
    }

    public static boolean isResumeReady() {
        return recoveryComplete;
    }

    public static boolean isWorldChangeRecoveryActive() {
        return recoveryMode == RecoveryMode.WORLD_CHANGE
                && worldChangePhase != WorldChangeRecoveryPhase.IDLE
                && worldChangeTargetPosition != null;
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (MacroStateManager.getCurrentState() != MacroState.State.RECOVERING || recoveryComplete) {
            return;
        }
        if (recoveryMode == RecoveryMode.WORLD_CHANGE) {
            updateWorldChangeRecovery(client);
            return;
        }

        updateCommandSequence(client, false);
    }

    private static void updateCommandSequence(Minecraft client, boolean worldChangeRecovery) {
        long now = System.currentTimeMillis();
        RecoverySequence.Action action = commandSequence.update(now, client == null ? null : client.level,
                ClientUtils.getCurrentLocation(), isClientReady(client));
        if (action == RecoverySequence.Action.RESUME) {
            if (worldChangeRecovery) {
                navigationWorld = client.level;
                startWorldChangePrimaryRecovery(client);
            } else {
                completeRecovery(client);
            }
        } else if (action.command != null) {
            ClientUtils.sendMessage("\u00A7e" + String.format(AetherLang.localize(
                    "Recovery: running %s, waiting for the destination to load..."), action.command), false);
            ClientUtils.sendCommand(action.command);
        }
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.getConnection() != null
                && client.screen == null && client.player.isAlive()
                && client.level.hasChunkAt(client.player.blockPosition());
    }

    private static boolean canResumeFarming(Minecraft client) {
        return MacroStateManager.getCurrentState() == MacroState.State.RECOVERING
                && isClientReady(client) && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN;
    }

    private static void completeRecovery(Minecraft client) {
        if (!canResumeFarming(client)) {
            return;
        }
        recoveryComplete = true;
        recoveryMode = null;
        ClientUtils.sendMessage("\u00A7aRecovery successful. Resuming farming...", false);
        ClientUtils.sendDebugMessage("Starting farming macro after confirming SkyBlock and Garden arrival");
        DynamicRestManager.scheduleNextRest();
        resumeFarming(client);
    }

    private static void resumeFarming(Minecraft client) {
        FailsafeManager.syncSelectedSlotFromClient(client);
        GearManager.swapToFarmingTool(client);
        FailsafeManager.syncSelectedSlotFromClient(client);
        MacroStateManager.setCurrentState(MacroState.State.FARMING);
        SqueakyMousematManager.armReapplyAttempt();
        FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig());
        FailsafeManager.syncSelectedSlotFromClient(client);
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

        if (client.level != navigationWorld || !canResumeFarming(client)) {
            worldChangePhase = WorldChangeRecoveryPhase.COMMAND_SEQUENCE;
            PathfindingManager.stop();
            RotationManager.cancelRotation();
            ClientUtils.forceReleaseKeys();
            worldChangeUsedWalkAssist = false;
            worldChangeAlignClicked = false;
            commandSequence.reset(System.currentTimeMillis());
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
        if (!canResumeFarming(client) || client.level != navigationWorld) {
            return;
        }
        recoveryComplete = true;
        recoveryMode = null;
        worldChangePhase = WorldChangeRecoveryPhase.IDLE;
        worldChangeTargetPosition = null;
        worldChangeWaitUntilMs = 0L;
        worldChangeUsedWalkAssist = false;
        worldChangeAlignClicked = false;
        navigationWorld = null;
        DynamicRestManager.scheduleNextRest();
        ClientUtils.sendMessage("\u00A7aWorld change recovery complete. Resuming farming...", false);
        resumeFarming(client);
    }

}
