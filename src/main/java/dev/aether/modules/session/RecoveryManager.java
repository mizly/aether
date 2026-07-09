package dev.aether.modules.session;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.FarmingMacroManager;
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
        STANDARD,
        PROXY_RESTART,
        WORLD_CHANGE
    }

    private enum WorldChangeRecoveryPhase {
        IDLE,
        WAITING,
        WAITING_FOR_SKYBLOCK,
        WAITING_FOR_GARDEN,
        WALK_PATH,
        PURE_ETHERWARP,
        WALK_ETHERWARP,
        ALIGNING,
        RESUME_DELAY
    }

    private static final long LIMBO_RECOVERY_DELAY_MS = 10_000L;
    private static final long WORLD_CHANGE_ALIGN_TIMEOUT_MS = 1200L;
    private static final long WORLD_CHANGE_GARDEN_RETRY_MS = 5000L;
    private static final long WORLD_CHANGE_RESUME_DELAY_MS = 1000L;
    private static int recoveryFailedAttempts = 0;
    private static long lastRecoveryActionTime = 0;
    private static MacroState.Location lastRecoveryLocation = MacroState.Location.UNKNOWN;
    private static long lastRecoveryLocationChangeTime = 0;
    private static boolean limboDelayAnnounced = false;
    private static RecoveryMode recoveryMode = RecoveryMode.STANDARD;
    private static WorldChangeRecoveryPhase worldChangePhase = WorldChangeRecoveryPhase.IDLE;
    private static Vec3 worldChangeTargetPosition = null;
    private static long worldChangeWaitUntilMs = 0L;
    private static boolean worldChangeUsedWalkAssist = false;
    private static boolean worldChangeAlignClicked = false;

    public static void reset() {
        recoveryFailedAttempts = 0;
        lastRecoveryActionTime = 0;
        lastRecoveryLocation = MacroState.Location.UNKNOWN;
        lastRecoveryLocationChangeTime = 0;
        limboDelayAnnounced = false;
        recoveryMode = RecoveryMode.STANDARD;
        worldChangePhase = WorldChangeRecoveryPhase.IDLE;
        worldChangeTargetPosition = null;
        worldChangeWaitUntilMs = 0L;
        worldChangeUsedWalkAssist = false;
        worldChangeAlignClicked = false;
    }

    public static void beginRecovery(RecoveryMode mode) {
        reset();
        recoveryMode = mode == null ? RecoveryMode.STANDARD : mode;
    }

    public static void beginWorldChangeRecovery(Vec3 savedPosition) {
        reset();
        recoveryMode = RecoveryMode.WORLD_CHANGE;
        worldChangePhase = WorldChangeRecoveryPhase.WAITING;
        worldChangeTargetPosition = savedPosition;
        worldChangeWaitUntilMs = System.currentTimeMillis()
                + Math.round(AetherConfig.FAILSAFE_WORLD_CHANGE_RECOVERY_WAIT_SECONDS.get() * 1000.0f);
        worldChangeUsedWalkAssist = false;
        worldChangeAlignClicked = false;
    }

    public static boolean isWorldChangeRecoveryActive() {
        return recoveryMode == RecoveryMode.WORLD_CHANGE
                && worldChangePhase != WorldChangeRecoveryPhase.IDLE
                && worldChangeTargetPosition != null;
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (recoveryMode == RecoveryMode.WORLD_CHANGE) {
            updateWorldChangeRecovery(client);
            return;
        }

        if (MacroStateManager.getCurrentState() != MacroState.State.RECOVERING)
            return;

        if (client.screen instanceof PauseScreen)
            return;

        if (System.currentTimeMillis() - lastRecoveryActionTime < 5000)
            return;

        MacroState.Location currentLoc = ClientUtils.getCurrentLocation(client);

        if (currentLoc != lastRecoveryLocation) {
            recoveryFailedAttempts = 0;
            lastRecoveryLocation = currentLoc;
            lastRecoveryLocationChangeTime = System.currentTimeMillis();
            limboDelayAnnounced = false;
        }

        if (recoveryFailedAttempts >= 15) {
            ClientUtils.sendMessage("\u00A7cAuto-recovery failed after 15 attempts. Stopping farming.", false);
            MacroStateManager.stopMacro(client);
            return;
        }

        lastRecoveryActionTime = System.currentTimeMillis();
        recoveryFailedAttempts++;

        switch (currentLoc) {
            case LIMBO:
                long limboElapsed = System.currentTimeMillis() - lastRecoveryLocationChangeTime;
                if (limboElapsed < LIMBO_RECOVERY_DELAY_MS) {
                    if (!limboDelayAnnounced) {
                        ClientUtils.sendMessage("\u00A7eIn Limbo. Waiting 10s before attempting recovery...", false);
                        limboDelayAnnounced = true;
                    }
                    lastRecoveryActionTime = 0;
                    recoveryFailedAttempts--;
                    return;
                }
                ClientUtils.sendMessage("\u00A7eRecovery (attempt "
                        + recoveryFailedAttempts + "): Warping to Lobby from Limbo...", false);
                ClientUtils.sendCommand(client, "/lobby");
                break;
            case LOBBY:
                if (recoveryMode == RecoveryMode.PROXY_RESTART) {
                    ClientUtils.sendMessage("\u00A7eRecovery (attempt "
                            + recoveryFailedAttempts + "): Rejoining SkyBlock with /play sb...", false);
                    ClientUtils.sendCommand(client, "/play sb");
                } else {
                    ClientUtils.sendMessage("\u00A7eRecovery (attempt "
                            + recoveryFailedAttempts + "): Warping to SkyBlock from Lobby...", false);
                    ClientUtils.sendCommand(client, "/skyblock");
                }
                break;
            case HUB:
            case UNKNOWN:
                ClientUtils.sendMessage("\u00A7eRecovery (attempt " + recoveryFailedAttempts + "): Warping to Garden...", false);
                ClientUtils.sendCommand(client, "/warp garden");
                break;
            case GARDEN:
                ClientUtils.sendMessage("\u00A7aRecovery successful. Resuming farming...", false);
                recoveryFailedAttempts = 0;
                recoveryMode = RecoveryMode.STANDARD;
                DynamicRestManager.scheduleNextRest();
                ClientUtils.sendDebugMessage("Starting farming macro after successful recovery");
                client.execute(() -> {
                    if (MacroStateManager.getCurrentState() != MacroState.State.RECOVERING) {
                        return;
                    }

                    // Keep the inventory-slot failsafe suppressed until the
                    // recovery resume path has restored the active tool/slot.
                    FailsafeManager.syncSelectedSlotFromClient(client);
                    GearManager.swapToFarmingTool(client);
                    FailsafeManager.syncSelectedSlotFromClient(client);
                    MacroStateManager.setCurrentState(MacroState.State.FARMING);
                    SqueakyMousematManager.armReapplyAttempt();
                    FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig());
                    FailsafeManager.syncSelectedSlotFromClient(client);
                });
                break;
        }
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

        if (worldChangePhase == WorldChangeRecoveryPhase.WAITING) {
            long now = System.currentTimeMillis();
            if (now < worldChangeWaitUntilMs) {
                return;
            }

            worldChangePhase = WorldChangeRecoveryPhase.WAITING_FOR_SKYBLOCK;
            worldChangeWaitUntilMs = now + randomWorldChangeSkyBlockWaitMs();
            ClientUtils.sendMessage("\u00A7eWorld change recovery: running /play sb...", false);
            ClientUtils.sendCommand(client, "/play sb");
            lastRecoveryActionTime = now;
            return;
        }

        if (worldChangePhase == WorldChangeRecoveryPhase.WAITING_FOR_SKYBLOCK) {
            long now = System.currentTimeMillis();
            if (now < worldChangeWaitUntilMs) {
                return;
            }

            worldChangePhase = WorldChangeRecoveryPhase.WAITING_FOR_GARDEN;
            worldChangeWaitUntilMs = 0L;
            ClientUtils.sendMessage("\u00A7eWorld change recovery: running /warp garden...", false);
            ClientUtils.sendCommand(client, "/warp garden");
            lastRecoveryActionTime = now;
            return;
        }

        if (worldChangePhase == WorldChangeRecoveryPhase.WAITING_FOR_GARDEN) {
            MacroState.Location location = ClientUtils.getCurrentLocation(client);
            if (location == MacroState.Location.GARDEN) {
                startWorldChangePrimaryRecovery(client);
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastRecoveryActionTime >= WORLD_CHANGE_GARDEN_RETRY_MS) {
                ClientUtils.sendDebugMessage("World change recovery: retrying /warp garden");
                ClientUtils.sendCommand(client, "/warp garden");
                lastRecoveryActionTime = now;
            }
            return;
        }

        if (worldChangePhase == WorldChangeRecoveryPhase.ALIGNING && !RotationManager.isRotating()) {
            if (!worldChangeAlignClicked) {
                worldChangeAlignClicked = true;
                GearManager.swapToAOTVSync(client);
                ClientUtils.performUseClick(client);
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
        recoveryMode = RecoveryMode.STANDARD;
        worldChangePhase = WorldChangeRecoveryPhase.IDLE;
        worldChangeTargetPosition = null;
        worldChangeWaitUntilMs = 0L;
        worldChangeUsedWalkAssist = false;
        worldChangeAlignClicked = false;
        DynamicRestManager.scheduleNextRest();
        ClientUtils.sendMessage("\u00A7aWorld change recovery complete. Resuming farming...", false);
        client.execute(() -> {
            if (recoveryMode != RecoveryMode.STANDARD) {
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

    private static long randomWorldChangeSkyBlockWaitMs() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000L, 15_001L);
    }
}

