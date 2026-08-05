package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import net.minecraft.client.Minecraft;

final class PestRoofAotvController {
    private static final long ROOF_AOTV_TIMEOUT_MS = 2_000L;

    private PestRoofAotvController() {
    }

    static boolean tick(Minecraft client, PestDestroyerRuntime runtime) {
        if (runtime.state == PestDestroyer.State.AOTV_TO_ROOF_RETURN) {
            releaseAotvKeys(client);
            if (!RotationManager.isRotating()) {
                PestAotvManager.setSneakingForAotv(false);
                PestDestroyer.completeRoofAotv();
            }
            return true;
        }
        if (runtime.state != PestDestroyer.State.AOTV_TO_ROOF) {
            return false;
        }

        if (Double.isNaN(runtime.aotvStartY)) {
            if (!PestAotvManager.isSneakingForAotv()) {
                PestDestroyer.completeRoofAotv();
            }
            return true;
        }

        PestAotvManager.setSneakingForAotv(true);
        int aotvSlot = GearManager.findAspectOfTheVoidSlot(client);
        if (aotvSlot >= 0
                && ((AccessorInventory) client.player.getInventory()).getSelected() == aotvSlot) {
            ClientUtils.setKeyMappingState(client.options.keyUse, true);
        }

        if (!PestAotvManager.hasRoofAbove(client)) {
            finishSuccessfulClimb(client, runtime);
        } else if (System.currentTimeMillis() - runtime.stateEnteredAt > ROOF_AOTV_TIMEOUT_MS) {
            recoverTimedOutClimb(client, runtime);
        }
        return true;
    }

    private static void finishSuccessfulClimb(Minecraft client, PestDestroyerRuntime runtime) {
        ClientUtils.sendDebugMessage("[PestDestroyer] AOTV Success: open air detected above.");
        runtime.aotvStartY = Double.NaN;
        releaseAotvKeys(client);
        float targetYaw = client.player.getYRot() + (float) (-10.0 + Math.random() * 20.0);
        boolean ballsackOnPlot = PestManager.isBallsackShredderActiveForCurrentCycle();
        float targetPitch = ballsackOnPlot
                ? 90.0f
                : (float) (-30.0 + Math.random() * 60.0);
        RotationManager.rotateToYawPitch(
                client, targetYaw, targetPitch, AetherConfig.ROTATION_TIME.get(), true);
        if (ballsackOnPlot) {
            PestAotvManager.setSneakingForAotv(false);
            PestDestroyer.setState(PestDestroyer.State.AOTV_POST_LOOKDOWN);
        } else {
            PestDestroyer.setState(PestDestroyer.State.AOTV_TO_ROOF_RETURN);
        }
    }

    private static void recoverTimedOutClimb(Minecraft client, PestDestroyerRuntime runtime) {
        ClientUtils.sendDebugMessage("[PestDestroyer] AOTV timed out. Issuing /plottp to recover.");
        runtime.aotvStartY = Double.NaN;
        runtime.roofAotvReturnState = null;
        PestAotvManager.setSneakingForAotv(false);
        releaseAotvKeys(client);

        String currentPlot = ClientUtils.getCurrentPlot();
        if (currentPlot == null || currentPlot.isEmpty()) {
            PestDestroyer.completeRoofAotv();
            return;
        }
        runtime.navigation.plotTpWindow = CommandUtils.beginChatWindow();
        CommandUtils.initiatePlotTp(currentPlot);
        runtime.navigation.lastTargetPlot = currentPlot;
        runtime.navigation.plotTpSent = true;
        PestDestroyer.setState(PestDestroyer.State.TELEPORT_TO_PLOT);
    }

    private static void releaseAotvKeys(Minecraft client) {
        ClientUtils.setKeyMappingState(client.options.keyShift, false);
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
    }
}
