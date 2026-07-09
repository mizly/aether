package dev.aether.modules.forge;

import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class ForgeManager {

    public static volatile boolean isRunning = false;

    // Coordinates of the target Forger NPC
    private static final int FORGE_NPC_X = -22;
    private static final int FORGE_NPC_Y = 151;
    private static final int FORGE_NPC_Z = -79;

    private static final int ROTATION_MS = 200;
    private static final long WARP_TIMEOUT_MS = 10_000;
    private static final long PATHFIND_TIMEOUT_MS = 30_000;

    public static void start(Minecraft client) {
        if (isRunning) {
            ClientUtils.sendMessage(client, "§c[Aether] Forge: already running.", false);
            return;
        }
        isRunning = true;
        MacroWorkerThread.getInstance().submit("ForgeManager", () -> {
            try {
                run(client);
            } catch (Exception e) {
                e.printStackTrace();
                ClientUtils.sendMessage(client, "§c[Aether] Forge error: " + e.getMessage(), false);
            } finally {
                isRunning = false;
            }
        });
    }

    public static void stop() {
        isRunning = false;
        PathfindingManager.stop();
    }

    private static void run(Minecraft client) throws InterruptedException {
        if (client.player == null) return;

        // Step 1: Warp to forge
        ClientUtils.sendMessage(client, "§e[Aether] Warping to forge...", false);
        if (!warpToForge(client)) {
            ClientUtils.sendMessage(client, "§c[Aether] Forge warp timed out. Aborting.", false);
            return;
        }
        MacroWorkerThread.sleepRandom(680, 240);

        if (!isRunning) return;

        // Step 2: Walk to nearest walkable block near the Forger NPC
        ClientUtils.sendMessage(client, "§e[Aether] Pathfinding to Forger NPC...", false);
        BlockPos walkTarget = findBestWalkingTarget(client, FORGE_NPC_X, FORGE_NPC_Y, FORGE_NPC_Z);
        if (walkTarget == null) walkTarget = new BlockPos(FORGE_NPC_X, FORGE_NPC_Y, FORGE_NPC_Z);
        if (!walkToCoords(client, walkTarget.getX(), walkTarget.getY(), walkTarget.getZ())) {
            ClientUtils.sendMessage(client, "§c[Aether] Failed to reach Forger NPC. Aborting.", false);
            return;
        }

        if (!isRunning) return;

        // Step 3: Find the Forger NPC entity (exclude real players)
        Entity forger = findForgerNpc(client);
        if (forger == null) {
            ClientUtils.sendMessage(client, "§c[Aether] Could not find Forger NPC. Aborting.", false);
            return;
        }

        ClientUtils.sendDebugMessage(client, "[ForgeManager] Found Forger at " + forger.position());

        // Step 4: Rotate to face it
        faceEntity(client, forger);

        if (!isRunning) return;

        // Step 5: Right-click until GUI opens
        ClientUtils.sendMessage(client, "§e[Aether] Opening forge menu...", false);
        if (!interactUntilGui(client, forger, 5000)) {
            ClientUtils.sendMessage(client, "§c[Aether] Forge GUI did not open. Aborting.", false);
            return;
        }

        ClientUtils.sendMessage(client, "§a[Aether] Forge menu opened successfully.", false);
        MacroWorkerThread.sleepRandom(255, 90);

        // Close the menu — we've confirmed it opened, nothing more to do yet
        client.execute(() -> {
            if (client.player != null) client.player.closeContainer();
        });
    }

    private static boolean warpToForge(Minecraft client) throws InterruptedException {
        if (client.player == null) return false;
        Vec3 startPos = client.player.position();

        client.execute(() -> ClientUtils.sendCommand(client, "/warp forge"));

        long deadline = System.currentTimeMillis() + WARP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && isRunning) {
            Thread.sleep(150);
            if (client.player == null) continue;
            if (client.player.position().distanceTo(startPos) > 10) {
                // Confirm we actually landed in Dwarven Mines
                long areaDeadline = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < areaDeadline && isRunning) {
                    String area = TablistUtils.findLine(client, "Area:");
                    if (area != null && area.contains("Dwarven")) return true;
                    Thread.sleep(150);
                }
                return false; // moved but not in Dwarven Mines
            }
        }
        return false;
    }

    /**
     * Finds the nearest walkable block within 3 blocks of the target NPC coords,
     * closest to the player. Mirrors the visitor macro's approach.
     */
    private static BlockPos findBestWalkingTarget(Minecraft client, int tx, int ty, int tz) {
        if (client.level == null || client.player == null) return null;
        BlockPos base = new BlockPos(tx, ty, tz);
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos pos = base.offset(x, y, z);
                    if (pos.distToCenterSqr(tx, ty, tz) > 9.0) continue; // within 3 blocks of NPC
                    if (!isWalkable(client, pos)) continue;
                    double dP = pos.distToCenterSqr(client.player.position());
                    if (dP < bestDistSq) {
                        bestDistSq = dP;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isWalkable(Minecraft client, BlockPos pos) {
        if (client.level == null) return false;
        return !client.level.getBlockState(pos.below()).getCollisionShape(client.level, pos.below()).isEmpty()
                && client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty()
                && client.level.getBlockState(pos.above()).getCollisionShape(client.level, pos.above()).isEmpty();
    }

    private static boolean walkToCoords(Minecraft client, int x, int y, int z) throws InterruptedException {
        client.execute(() -> PathfindingManager.startPathfind(client, x, y, z, false));
        Thread.sleep(340 + (long)(Math.random() * 120));

        long deadline = System.currentTimeMillis() + PATHFIND_TIMEOUT_MS;
        while (PathfindingManager.isNavigating() && System.currentTimeMillis() < deadline && isRunning) {
            Thread.sleep(200);
        }

        if (PathfindingManager.isNavigating()) {
            PathfindingManager.stop();
            return false;
        }
        return true;
    }

    /**
     * Finds the nearest "Forger" NPC, explicitly excluding real players.
     */
    private static Entity findForgerNpc(Minecraft client) {
        if (client.level == null || client.player == null) return null;

        Entity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) continue;
            if (entity instanceof Player) continue; // exclude real players

            String name = entity.getName().getString()
                    .replaceAll("(?i)§.", "").trim().toLowerCase();
            if (!name.contains("forger")) continue;

            double dist = entity.distanceToSqr(client.player);
            if (dist < closestDist) {
                closestDist = dist;
                closest = entity;
            }
        }

        return closest;
    }

    private static void faceEntity(Minecraft client, Entity entity) throws InterruptedException {
        client.execute(() -> RotationManager.initiateRotation(
                client,
                new Vec3(entity.getX(), entity.getEyeY(), entity.getZ()),
                ROTATION_MS, 0f));
        Thread.sleep(ROTATION_MS + 40 + (long)(Math.random() * 30));
    }

    private static boolean interactUntilGui(Minecraft client, Entity entity, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && isRunning) {
            ClientUtils.performUseClick(client);
            for (int i = 0; i < 5; i++) {
                Thread.sleep(100);
                if (client.screen instanceof AbstractContainerScreen) return true;
                if (!isRunning) return false;
            }
        }
        return client.screen instanceof AbstractContainerScreen;
    }
}
