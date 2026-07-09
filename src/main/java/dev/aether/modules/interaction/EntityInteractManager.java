package dev.aether.modules.interaction;

import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.EntityUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class EntityInteractManager {
    private static final long PATH_TIMEOUT_MS = 20_000L;
    private static final long INTERACT_ROTATION_MS = 150L;
    private static final float INTERACT_RANGE_THRESHOLD = 2.5f;
    private static final double WALK_TARGET_RADIUS_SQ = 4.0;

    private EntityInteractManager() {
    }

    public static void start(Minecraft client, String entityName) {
        if (client == null || client.player == null) {
            return;
        }

        String targetName = entityName == null ? "" : entityName.trim();
        if (targetName.isEmpty()) {
            ClientUtils.sendMessage("\u00A7eUsage: /aether interact <entity_name>", false);
            return;
        }

        MacroWorkerThread.getInstance().submit("Interact-" + targetName, () -> run(client, targetName));
    }

    private static void run(Minecraft client, String entityName) {
        Entity target = EntityUtils.findEntity(client, entityName);
        if (target == null) {
            ClientUtils.sendMessage("\u00A7cCould not find entity: \u00A7e" + entityName, false);
            dumpNearbyEntityDebug(client, entityName, "initial lookup");
            return;
        }

        ClientUtils.sendMessage("\u00A7ePathfinding to \u00A7e" + target.getName().getString() + "\u00A76...", false);

        if (client.player.distanceTo(target) > INTERACT_RANGE_THRESHOLD) {
            BlockPos walkTarget = findBestWalkingTarget(client, target);
            if (walkTarget == null) {
                walkTarget = target.blockPosition();
            }

            int x = walkTarget.getX();
            int y = walkTarget.getY();
            int z = walkTarget.getZ();
            Entity pathTarget = target;

            client.execute(() -> PathfindingManager.startPathfind(client, x, y, z, false, pathTarget));
            MacroWorkerThread.sleepRandom(255, 90);

            long deadline = System.currentTimeMillis() + PATH_TIMEOUT_MS;
            while (PathfindingManager.isNavigating() && System.currentTimeMillis() < deadline) {
                MacroWorkerThread.sleep(100);
            }

            if (PathfindingManager.isNavigating()) {
                PathfindingManager.stop();
                ClientUtils.sendMessage("\u00A7cTimed out pathfinding to entity: \u00A7e" + entityName, false);
                return;
            }
        }

        Entity refreshedTarget = EntityUtils.findEntity(client, entityName);
        if (refreshedTarget != null) {
            target = refreshedTarget;
        } else {
            ClientUtils.sendDebugMessage("Interact: target missing after pathing for \"" + entityName + "\"");
            dumpNearbyEntityDebug(client, entityName, "post-path lookup");
        }

        ClientUtils.sendMessage("\u00A7eInteracting with \u00A7e" + target.getName().getString() + "\u00A76...", false);

        Vec3 targetPos = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        client.execute(() -> RotationManager.initiateRotation(client, targetPos, INTERACT_ROTATION_MS));
        MacroWorkerThread.sleepRandom(INTERACT_ROTATION_MS + 60L, 30L);

        ClientUtils.performUseClick(client);
        MacroWorkerThread.sleepRandom(85, 30);
        ClientUtils.sendMessage("\u00A7aInteracted with \u00A7e" + target.getName().getString(), false);
    }

    private static void dumpNearbyEntityDebug(Minecraft client, String entityName, String phase) {
        ClientUtils.sendDebugMessage("Interact: no match for \"" + entityName + "\" during " + phase);
        for (String line : EntityUtils.describeNearbyEntities(client, 8.0, 12)) {
            ClientUtils.sendDebugMessage("Interact: " + line);
        }
    }

    private static BlockPos findBestWalkingTarget(Minecraft client, Entity entity) {
        if (client.level == null || client.player == null) {
            return null;
        }

        BlockPos base = entity.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos pos = base.offset(x, y, z);
                    double distanceToEntity = pos.distToCenterSqr(entity.position());
                    if (distanceToEntity > WALK_TARGET_RADIUS_SQ) {
                        continue;
                    }

                    if (!isWalkable(client, pos)) {
                        continue;
                    }

                    double distanceToPlayer = pos.distToCenterSqr(client.player.position());
                    if (distanceToPlayer < bestDistSq) {
                        bestDistSq = distanceToPlayer;
                        best = pos;
                    }
                }
            }
        }

        return best;
    }

    private static boolean isWalkable(Minecraft client, BlockPos pos) {
        if (client.level == null) {
            return false;
        }

        return !client.level.getBlockState(pos.below()).getCollisionShape(client.level, pos.below()).isEmpty()
                && client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty()
                && client.level.getBlockState(pos.above()).getCollisionShape(client.level, pos.above()).isEmpty();
    }
}
