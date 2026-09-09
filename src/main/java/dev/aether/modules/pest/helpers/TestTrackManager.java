package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.rotation.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Temporary test aid for comparing pest aim feel without clicking or interacting.
 * {@code /testtrack} runs a smoother experimental pursuit; {@code /testoriginal}
 * runs the current in-game combat tracking smoothing as a baseline. Tracking
 * pauses whenever a screen is open so chat can be used to toggle it back off.
 */
public final class TestTrackManager {
    private static final float ORIGINAL_SMOOTHING_MS = 190.0f;
    private static final float HUMAN_SMOOTHING_MS = 130.0f;

    private enum Mode { OFF, HUMAN, ORIGINAL }

    private static Mode mode = Mode.OFF;

    private TestTrackManager() {
    }

    public static boolean toggleHuman() {
        mode = (mode == Mode.HUMAN) ? Mode.OFF : Mode.HUMAN;
        if (mode == Mode.OFF) {
            RotationManager.cancelRotation();
        }
        return mode == Mode.HUMAN;
    }

    public static boolean toggleOriginal() {
        mode = (mode == Mode.ORIGINAL) ? Mode.OFF : Mode.ORIGINAL;
        if (mode == Mode.OFF) {
            RotationManager.cancelRotation();
        }
        return mode == Mode.ORIGINAL;
    }

    public static void tick(Minecraft client) {
        if (mode == Mode.OFF || client == null || client.player == null || client.level == null) {
            return;
        }
        // Pause while any screen is open so chat can be used to toggle it off and
        // the camera does not fight the player's input.
        if (client.screen != null) {
            RotationManager.cancelRotation();
            return;
        }
        Entity nearest = nearestPest(client);
        if (nearest == null) {
            RotationManager.cancelRotation();
            return;
        }
        float smoothing = (mode == Mode.ORIGINAL) ? ORIGINAL_SMOOTHING_MS : HUMAN_SMOOTHING_MS;
        RotationManager.trackRotation(
                client,
                nearest.getEyePosition(),
                smoothing,
                AetherConfig.PEST_MAX_TURN_SPEED.get());
    }

    private static Entity nearestPest(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity entity : PestTargetTracker.getLoadedPestMobs(client)) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            double distSq = entity.position().distanceToSqr(eye);
            if (distSq < bestSq) {
                bestSq = distSq;
                best = entity;
            }
        }
        return best;
    }
}
