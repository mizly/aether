package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.EntityUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Handles the Pest Exchange flow:
 * 1. /plottp barn
 * 2. Pathfind walk to Phillip's desk (configurable position)
 * 3. Interact with Phillip NPC
 * 4. Click "Empty Vacuum Bag" in the Pesthunter GUI
 */
public class PestExchangeManager {
    private static final int MAX_ABIPHONE_CALL_ATTEMPTS = 3;
    private static final long ABIPHONE_GUI_WAIT_MS = 10000L;

    public static volatile boolean isExchanging = false;
    private static volatile int interactionStage = 0;
    private static volatile long interactionTime = 0;

    public static void start(Minecraft client) {
        if (client.player == null) return;

        MacroWorkerThread.getInstance().submit("PestExchange", () -> runExchangeBlocking(client));
    }

    public static boolean runExchangeBlocking(Minecraft client) {
        if (isExchanging) {
            ClientUtils.sendMessage("§cPest exchange is already running.", false);
            return false;
        }
        if (client.player == null) return false;

        isExchanging = true;
        interactionStage = 0;
        interactionTime = 0;

        ClientUtils.sendMessage("§eStarting pest exchange...", false);

        try {
            return runExchange(client);
        } catch (Exception e) {
            client.execute(() -> {
                if (client.player != null)
                    ClientUtils.sendMessage("§cPest exchange error: " + e.getMessage(), false);
            });
            e.printStackTrace();
            return false;
        } finally {
            isExchanging = false;
            interactionStage = 0;
            PathfindingManager.stop();
        }
    }

    public static void stop() {
        isExchanging = false;
        interactionStage = 0;
        PathfindingManager.stop();
    }

    public static void reset() {
        isExchanging = false;
        interactionStage = 0;
        interactionTime = 0;
    }

    private static boolean runExchange(Minecraft client) {
        // Step 1: Close any open screen
        if (client.screen != null) {
            client.execute(() -> {
                if (client.player != null) client.player.closeContainer();
            });
            MacroWorkerThread.sleep(500);
        }

        if (!isExchanging) return false;

        if (AetherConfig.AUTO_PEST_USE_ABIPHONE.get()) {
            return runAbiphoneExchange(client);
        }

        GearManager.swapToFarmingToolSync(client);

        // Step 2: /plottp barn
        ClientUtils.sendDebugMessage("[PestExchange] Teleporting to barn...");
        client.execute(() -> {
            if (client.player != null)
                ClientUtils.sendMessage("§eTeleporting to barn...", false);
        });

        Vec3 posBefore = client.player.position();
        dev.aether.modules.failsafe.FailsafeManager.addRotationGracePeriod(dev.aether.config.AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        ClientUtils.sendCommand(client, "/plottp barn");
        MacroWorkerThread.sleep(600);

        // Wait for teleport (up to 5 seconds)
        long tpDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < tpDeadline && isExchanging) {
            if (client.player != null && client.player.position().distanceTo(posBefore) > 5) {
                break;
            }
            MacroWorkerThread.sleep(200);
        }

        if (!isExchanging) return false;
        MacroWorkerThread.sleep(500);

        // Step 3: Pathfind walk to desk position
        int deskX = AetherConfig.PEST_EXCHANGE_DESK_X.get();
        int deskY = AetherConfig.PEST_EXCHANGE_DESK_Y.get();
        int deskZ = AetherConfig.PEST_EXCHANGE_DESK_Z.get();

        ClientUtils.sendDebugMessage("[PestExchange] Walking to desk at " + deskX + ", " + deskY + ", " + deskZ);
        client.execute(() -> {
                if (client.player != null)
                    ClientUtils.sendMessage("§eWalking to Phillip's desk...", false);
        });

        client.execute(() -> PathfindingManager.startPathfind(client, deskX, deskY, deskZ, false));

        // Wait for pathfinding to complete (up to 30 seconds)
        MacroWorkerThread.sleep(1000); // Give pathfinder time to start
        long pathDeadline = System.currentTimeMillis() + 30000;
        while (PathfindingManager.isNavigating() && System.currentTimeMillis() < pathDeadline && isExchanging) {
            MacroWorkerThread.sleep(200);
        }

        if (!isExchanging) return false;

        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop();
            client.execute(() -> {
                if (client.player != null)
                    ClientUtils.sendMessage("§cPathfinding timed out. Stopping pest exchange.", false);
            });
            isExchanging = false;
            return false;
        }

        MacroWorkerThread.sleep(300);

        // Step 4: Find and interact with Phillip
        ClientUtils.sendDebugMessage("[PestExchange] Looking for Phillip...");

        Entity phillipEntity = EntityUtils.findEntity(client, "Phillip");
        if (phillipEntity == null) {
            client.execute(() -> {
                if (client.player != null)
                    ClientUtils.sendMessage("§ePhillip not found, retrying in 3s...", false);
            });
            MacroWorkerThread.sleep(3000);
            if (!isExchanging) return false;
            phillipEntity = EntityUtils.findEntity(client, "Phillip");
        }

        if (phillipEntity == null) {
            client.execute(() -> {
                if (client.player != null)
                    ClientUtils.sendMessage("§cCould not find Phillip NPC after retry. Stopping.", false);
            });
            isExchanging = false;
            return false;
        }

        final Entity phillip = phillipEntity;
        ClientUtils.sendDebugMessage("[PestExchange] Found Phillip, rotating...");

        GearManager.swapToFarmingToolSync(client);
        facePhillipForInteraction(client, phillip);

        // left-click Phillip
        ClientUtils.sendDebugMessage("[PestExchange] Interacting with Phillip...");
        ClientUtils.performAttackClick(client);

        // Wait for GUI to appear (up to 5 seconds)
        interactionStage = 1; // Waiting for Pesthunter GUI
        long guiDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < guiDeadline && isExchanging) {
            if (client.screen instanceof AbstractContainerScreen<?> screen) {
                String title = screen.getTitle().getString().toLowerCase();
                if (title.contains("pesthunter") || title.contains("phillip")) {
                    ClientUtils.sendDebugMessage("[PestExchange] Pesthunter GUI opened!");
                    break;
                }
            }
            MacroWorkerThread.sleep(200);
        }

        if (!isExchanging) return false;

        if (!(client.screen instanceof AbstractContainerScreen)) {
            // Try clicking again
            ClientUtils.sendDebugMessage("[PestExchange] GUI didn't open, retrying click...");
            facePhillipForInteraction(client, phillip);
            ClientUtils.performUseClick(client);

            guiDeadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < guiDeadline && isExchanging) {
                if (client.screen instanceof AbstractContainerScreen<?> screen) {
                    String title = screen.getTitle().getString().toLowerCase();
                    if (title.contains("pesthunter") || title.contains("phillip")) {
                        break;
                    }
                }
                MacroWorkerThread.sleep(200);
            }
        }

        if (!(client.screen instanceof AbstractContainerScreen)) {
            client.execute(() -> {
                if (client.player != null)
                    ClientUtils.sendMessage("§cFailed to open Phillip's GUI. Stopping.", false);
            });
            isExchanging = false;
            return false;
        }

        // Step 5: Click "Empty Vacuum Bag" in the Pesthunter GUI
        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true));

        if (!isExchanging) return false;

        client.execute(() -> {
            if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;

            int vacuumSlot = findVacuumSlot(screen);
            if (vacuumSlot == -1) {
                    if (client.player != null)
                    ClientUtils.sendMessage("§cCould not find 'Empty Vacuum Bag' slot. Closing.", false);
                client.player.closeContainer();
                isExchanging = false;
                return;
            }

            ItemStack vacuumStack = screen.getMenu().slots.get(vacuumSlot).getItem();
            List<Component> tooltipLines = vacuumStack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.EMPTY, client.player,
                    net.minecraft.world.item.TooltipFlag.NORMAL);
            String lore = tooltipLinesToString(tooltipLines);

            if (lore.contains("Click to empty")) {
                if (client.player != null)
                    ClientUtils.sendMessage("§aEmptying vacuum bag!", false);
                dev.aether.util.ClientUtils.performSlotClick(client, screen, vacuumSlot, 0, ContainerInput.PICKUP);
            } else if (lore.contains("exchanged enough Pests")) {
                if (client.player != null)
                    ClientUtils.sendMessage("§eAlready emptied the vacuum recently!", false);
                client.player.closeContainer();
            } else {
                if (client.player != null)
                    ClientUtils.sendMessage("§cVacuum bag state unknown. Closing.", false);
                client.player.closeContainer();
            }
        });

        // Wait a bit for server response
        MacroWorkerThread.sleep(1500);

        // Close GUI if still open
        client.execute(() -> {
            if (client.screen != null && client.player != null) {
                client.player.closeContainer();
            }
        });

        MacroWorkerThread.sleep(300);

        client.execute(() -> {
            if (client.player != null)
                ClientUtils.sendMessage("§aPest exchange complete!", false);
        });

        isExchanging = false;
        return true;
    }

    private static boolean runAbiphoneExchange(Minecraft client) {
        for (int attempt = 1; attempt <= MAX_ABIPHONE_CALL_ATTEMPTS && isExchanging; attempt++) {
            final int currentAttempt = attempt;
            ClientUtils.sendDebugMessage("PestExchange: calling Phillip via Abiphone, attempt "
                    + currentAttempt + "/" + MAX_ABIPHONE_CALL_ATTEMPTS);
            client.execute(() -> ClientUtils.sendMessage("§eCalling Phillip via Abiphone (" + currentAttempt + "/" + MAX_ABIPHONE_CALL_ATTEMPTS + ")...",
                    false));
            dev.aether.util.ClientUtils.sendCommand(client, "/call phillip");

            if (waitForPesthunterGui(client, ABIPHONE_GUI_WAIT_MS)) {
                ClientUtils.sendDebugMessage("PestExchange: Pesthunter GUI opened via Abiphone");
                handlePesthunterGuiActions(client);
                isExchanging = false;
                return true;
            }
        }

        if (isExchanging) {
            client.execute(() -> {
                if (client.player != null) {
                    ClientUtils.sendMessage("§cCould not open Phillip's GUI after 3 Abiphone calls. Stopping pest exchange.",
                            false);
                }
            });
        }
        isExchanging = false;
        return false;
    }

    private static boolean waitForPesthunterGui(Minecraft client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && isExchanging) {
            if (isPesthunterGuiOpen(client)) {
                return true;
            }
            MacroWorkerThread.sleep(200);
        }
        return false;
    }

    private static boolean isPesthunterGuiOpen(Minecraft client) {
        if (client.screen instanceof AbstractContainerScreen<?> screen) {
            String title = screen.getTitle().getString().toLowerCase();
            return title.contains("pesthunter") || title.contains("phillip");
        }
        return false;
    }

    private static void handlePesthunterGuiActions(Minecraft client) {
        // This method assumes the Pesthunter GUI is currently open.
        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true));

        client.execute(() -> {
            if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;

            int vacuumSlot = findVacuumSlot(screen);
            if (vacuumSlot == -1) {
                if (client.player != null)
                    ClientUtils.sendMessage("§cCould not find 'Empty Vacuum Bag' slot. Closing.", false);
                client.player.closeContainer();
                return;
            }

            ItemStack vacuumStack = screen.getMenu().slots.get(vacuumSlot).getItem();
            List<Component> tooltipLines = vacuumStack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.EMPTY, client.player,
                    net.minecraft.world.item.TooltipFlag.NORMAL);
            String lore = tooltipLinesToString(tooltipLines);

            if (lore.contains("Click to empty")) {
                if (client.player != null)
                    ClientUtils.sendMessage("§aEmptying vacuum bag!", false);
                dev.aether.util.ClientUtils.performSlotClick(client, screen, vacuumSlot, 0, ContainerInput.PICKUP);
            } else if (lore.contains("exchanged enough Pests")) {
                if (client.player != null)
                    ClientUtils.sendMessage("§eAlready emptied the vacuum recently!", false);
                client.player.closeContainer();
            } else {
                if (client.player != null)
                    ClientUtils.sendMessage("§cVacuum bag state unknown. Closing.", false);
                client.player.closeContainer();
            }
        });

        MacroWorkerThread.sleep(1500);

        client.execute(() -> {
            if (client.screen != null && client.player != null) {
                client.player.closeContainer();
            }
        });

        MacroWorkerThread.sleep(300);

        client.execute(() -> {
            if (client.player != null)
                ClientUtils.sendMessage("§aPest exchange complete!", false);
        });
    }

    private static int findVacuumSlot(AbstractContainerScreen<?> screen) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) continue;
            String name = slot.getItem().getHoverName().getString()
                    .replaceAll("(?i)§.", "").toLowerCase();
            if (name.contains("empty vacuum") || name.contains("vacuum bag")) {
                return i;
            }
        }
        return -1;
    }

    private static void facePhillipForInteraction(Minecraft client, Entity phillip) {
        rotateToPhillip(client, phillip);
        waitForRotationToFinish();
        if (!isLookingAt(client, phillip, AetherConfig.PEST_EXCHANGE_FOV_RANGE.get())) {
            MacroWorkerThread.sleep(100);
        }
    }

    private static void rotateToPhillip(Minecraft client, Entity phillip) {
        client.execute(() -> RotationManager.initiateRotation(client,
                new Vec3(phillip.getX(), phillip.getEyeY(), phillip.getZ()),
                AetherConfig.ROTATION_TIME.get(),
                AetherConfig.PEST_EXCHANGE_FOV_RANGE.get()));
        MacroWorkerThread.sleep(AetherConfig.ROTATION_TIME.get() + 50L);
    }

    private static void waitForRotationToFinish() {
        long deadline = System.currentTimeMillis() + 1500L;
        while (RotationManager.isRotating() && System.currentTimeMillis() < deadline) {
            MacroWorkerThread.sleep(25);
        }
    }

    private static boolean isLookingAt(Minecraft client, Entity entity, float tolerance) {
        if (client.player == null) {
            return false;
        }
        return RotationUtils.isLookingAt(client.player.getYRot(), client.player.getXRot(),
                client.player.getEyePosition(), entity.getEyePosition(), tolerance);
    }

    private static String tooltipLinesToString(List<Component> lines) {
        StringBuilder sb = new StringBuilder();
        for (Component c : lines) {
            sb.append(c.getString()).append(" ");
        }
        return sb.toString();
    }
}

