package dev.aether.modules.pest.helpers;

import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

/** Handles vacuum selection and entering flight before a hunt begins. */
final class PestEquipmentController {
    interface Context {
        void setState(PestDestroyer.State state);

        void finish(Minecraft client);
    }

    private PestEquipmentController() {
    }

    static void equipVacuum(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        boolean hunting = PestHuntingController.isEnabled();
        boolean hasLasso = false;
        if (hunting) {
            runtime.lassoSlot = PestLoadoutHelper.findLassoHotbarSlot(client);
            hasLasso = runtime.lassoSlot != -1;
            if (!hasLasso) {
                ClientUtils.sendMessage(
                        "\u00A7cNo lasso found in hotbar. Lasso-routed pests will be left untouched.", false);
            }
        }

        int[] vacuumSlots = PestLoadoutHelper.findAutomaticVacuumSlots(client);
        runtime.stunVacuumSlot = vacuumSlots[0];
        int slot = vacuumSlots[1];
        if (slot == -1) {
            // Hunting only taps the vacuum to stun, so it can run without one.
            if (hunting && hasLasso) {
                ClientUtils.sendDebugMessage(
                        "[PestHunting] No vacuum in hotbar. Skipping the stun tap.");
                runtime.vacuumSlot = -1;
                enterFlightOrFinish(client, context);
                return;
            }
            ClientUtils.sendMessage(
                    "\u00A7cNo vacuum found in hotbar. Aborting pest destroyer.", false);
            context.finish(client);
            return;
        }

        runtime.vacuumSlot = slot;
        runtime.killVacuumSlot = slot;
        runtime.vacuumRange = PestLoadoutHelper.detectVacuumRange(client, slot);
        client.execute(
                () -> FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot));
        ClientUtils.sendDebugMessage(
                "[PestDestroyer] Equipped vacuum (slot "
                        + runtime.vacuumSlot
                        + ", range "
                        + runtime.vacuumRange
                        + ")");

        enterFlightOrFinish(client, context);
    }

    private static void enterFlightOrFinish(Minecraft client, Context context) {
        if (!client.player.getAbilities().flying
                && client.player.getAbilities().mayfly) {
            context.setState(PestDestroyer.State.FLY_UP);
        } else if (client.player.getAbilities().flying) {
            context.setState(PestDestroyer.State.CHECK_NEXT);
        } else {
            ClientUtils.sendMessage(
                    "\u00A7cCannot fly. Aborting pest destroyer.", false);
            context.finish(client);
        }
    }

    static void enterFlight(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        long elapsed = System.currentTimeMillis() - runtime.stateEnteredAt;
        if (client.player.getAbilities().flying) {
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
            context.setState(PestDestroyer.State.CHECK_NEXT);
            return;
        }

        if (elapsed < 50) {
            ClientUtils.setKeyMappingState(client.options.keyJump, true);
        } else if (elapsed < 100) {
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
        } else if (elapsed < 150) {
            ClientUtils.setKeyMappingState(client.options.keyJump, true);
        } else if (elapsed < 200) {
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
        } else if (elapsed < 3_000) {
            runtime.stateEnteredAt = System.currentTimeMillis();
        } else {
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
            context.setState(PestDestroyer.State.CHECK_NEXT);
        }
    }
}
