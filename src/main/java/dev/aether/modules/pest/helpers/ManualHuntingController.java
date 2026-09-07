package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroState;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pest.ManualPestManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Aim and reel assists for Manual Pest Mode. Both only ever act on a pest the
 * player has personally lassoed, so they cannot fire during ordinary farming.
 */
public final class ManualHuntingController {
    private static final long REEL_CLICK_COOLDOWN_MS = 250L;
    private static final double MARKER_SEARCH_SIZE = 8.0;
    private static final double MARKER_MAX_HORIZONTAL = 3.0;
    // Below this the crosshair is already on the pest; nudging further only jitters.
    private static final float AIM_DEADZONE_DEGREES = 1.5f;
    private static final float AIM_MAX_TURN_SPEED = 360.0f;
    private static final long MAX_TURN_STEP_MS = 60L;
    // Keep the aim assist alive briefly after the reel prompt blinks out so
    // intermittent detection does not make it stutter on and off.
    private static final long HOOKED_GRACE_MS = 750L;

    private static boolean reelLatched = false;
    private static long lastReelClickAt = 0L;
    private static long promptSince = 0L;
    private static long reelDelayMs = 0L;
    private static long hookedUntil = 0L;
    private static long lastAimTickAt = 0L;

    private ManualHuntingController() {
    }

    public static void reset() {
        reelLatched = false;
        lastReelClickAt = 0L;
        promptSince = 0L;
        reelDelayMs = 0L;
        hookedUntil = 0L;
        lastAimTickAt = 0L;
    }

    public static void tick(Minecraft client) {
        if (!huntingActive(client)) {
            reset();
            return;
        }
        Entity pest = findLeashedPest(client);
        boolean autoreel = AetherConfig.MANUAL_HUNT_AUTOREEL.get();
        boolean aimAssist = AetherConfig.MANUAL_HUNT_AIM_ASSIST.get();
        if (!autoreel && !aimAssist) {
            reelLatched = false;
            promptSince = 0L;
            hookedUntil = 0L;
            return;
        }
        if (pest == null) {
            reelLatched = false;
            promptSince = 0L;
            hookedUntil = 0L;
            return;
        }
        // The struggle bar comes up the moment a pest is hooked and before the reel
        // prompt, so it is what gates the aim assist onto the camera.
        if (aimAssist && hasStruggleBar(client, pest)) {
            hookedUntil = System.currentTimeMillis() + HOOKED_GRACE_MS;
        }
        if (autoreel) {
            tickAutoreel(hasReelPrompt(client, pest));
        } else {
            reelLatched = false;
            promptSince = 0L;
        }
    }

    // Runs every render frame so the assisted camera moves smoothly instead of
    // snapping once per game tick. Gated on the pest being hooked (bar up).
    public static void tickAim(Minecraft client) {
        if (!huntingActive(client)
                || !AetherConfig.MANUAL_HUNT_AIM_ASSIST.get()
                || System.currentTimeMillis() > hookedUntil) {
            lastAimTickAt = 0L;
            return;
        }
        Entity pest = findLeashedPest(client);
        if (pest == null) {
            lastAimTickAt = 0L;
            return;
        }
        tickAimAssist(client, pest);
    }

    private static boolean huntingActive(Minecraft client) {
        return client != null && client.player != null && client.level != null
                && client.screen == null
                && AetherConfig.MANUAL_PEST_MODE.get()
                && AetherConfig.MANUAL_PEST_HUNTING.get()
                && ManualPestManager.isActive()
                && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN;
    }

    private static void tickAutoreel(boolean promptUp) {
        if (!promptUp) {
            reelLatched = false;
            promptSince = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        // Sample a fresh offset each time the prompt appears so the reel lands a
        // little later, mirroring the pest destroyer trigger delay.
        if (promptSince == 0L) {
            promptSince = now;
            reelDelayMs = ConfigHelpers.getRandomizedDelay(
                    AetherConfig.MANUAL_HUNT_REEL_DELAY_MIN.get(),
                    AetherConfig.MANUAL_HUNT_REEL_DELAY_MAX.get());
        }
        if (reelLatched
                || now - promptSince < reelDelayMs
                || now - lastReelClickAt < REEL_CLICK_COOLDOWN_MS) {
            return;
        }
        ClientUtils.performUseClickNow();
        reelLatched = true;
        lastReelClickAt = now;
    }

    private static void tickAimAssist(Minecraft client, Entity pest) {
        if (RotationManager.isRotating()) {
            lastAimTickAt = 0L;
            return;
        }
        Vec3 aimPoint = pest.position().add(0, pest.getBbHeight() * 0.6, 0);
        RotationUtils.Rotation target =
                RotationUtils.calculateLookAt(client.player.getEyePosition(), aimPoint);
        float remainingYaw = Mth.wrapDegrees(target.yaw - client.player.getYRot());
        float remainingPitch = Mth.clamp(target.pitch, -90.0f, 90.0f) - client.player.getXRot();

        float fov = Math.max(1, AetherConfig.MANUAL_HUNT_AIM_FOV.get());
        float angularError = Math.max(Math.abs(remainingYaw), Math.abs(remainingPitch));
        // Only help while the pest is already roughly under the crosshair. Outside
        // that the user is looking elsewhere on purpose, so leave the camera alone.
        if (angularError > fov || angularError < AIM_DEADZONE_DEGREES) {
            lastAimTickAt = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        long stepMs = lastAimTickAt == 0L ? 50L : Math.min(now - lastAimTickAt, MAX_TURN_STEP_MS);
        lastAimTickAt = now;

        int strength = Mth.clamp(AetherConfig.MANUAL_HUNT_AIM_STRENGTH.get(), 1, 10);
        float smoothingMs = 350.0f - strength * 28.0f;
        float closed = 1.0f - (float) Math.exp(-stepMs / smoothingMs);

        float yawStep = remainingYaw * closed;
        float pitchStep = remainingPitch * closed;
        float budget = AIM_MAX_TURN_SPEED * stepMs / 1000.0f;
        float step = (float) Math.sqrt(yawStep * yawStep + pitchStep * pitchStep);
        if (step > budget && step > 0.0f) {
            float scale = budget / step;
            yawStep *= scale;
            pitchStep *= scale;
        }

        float newYaw = client.player.getYRot() + yawStep;
        float newPitch = Mth.clamp(client.player.getXRot() + pitchStep, -90.0f, 90.0f);
        client.player.setYRot(newYaw);
        client.player.setXRot(newPitch);
        client.player.yRotO = newYaw;
        client.player.xRotO = newPitch;
        // Keep the rotation failsafe in step with the assisted crosshair so it does
        // not read our nudge (or the user's own aiming) as an unexpected rotation.
        FailsafeManager.expectRotation(newYaw, newPitch);
    }

    // The lasso struggle bar is an armor stand ~2 blocks above the hooked pest
    // whose name has a bold+strikethrough run (the strikethrough draws the bar; its
    // text is only spaces, so it must be read from the style, the way SkyHanni's
    // LassoDisplay does). It shows before the reel prompt, so it is the earliest
    // reliable "pest is hooked" signal.
    private static boolean hasStruggleBar(Minecraft client, Entity pest) {
        AABB box = AABB.ofSize(pest.position().add(0, 2.0, 0), 4.0, 4.0, 4.0);
        for (ArmorStand marker : client.level.getEntitiesOfClass(ArmorStand.class, box)) {
            Component name = marker.getCustomName();
            if (name != null && isBoldStrikethrough(name, false, false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBoldStrikethrough(Component component, boolean bold, boolean strike) {
        Style style = component.getStyle();
        boolean b = bold || style.isBold();
        boolean s = strike || style.isStrikethrough();
        if (b && s) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (isBoldStrikethrough(sibling, b, s)) {
                return true;
            }
        }
        return false;
    }

    private static Entity findLeashedPest(Minecraft client) {
        Vec3 playerPos = client.player.position();
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!isPestMob(entity)
                    || !(entity instanceof Leashable leashable)
                    || leashable.getLeashHolder() != client.player) {
                continue;
            }
            double distance = entity.position().distanceToSqr(playerPos);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = entity;
            }
        }
        return closest;
    }

    private static boolean hasReelPrompt(Minecraft client, Entity pest) {
        AABB searchBox = AABB.ofSize(pest.position().add(0, 1.5, 0),
                MARKER_SEARCH_SIZE, MARKER_SEARCH_SIZE, MARKER_SEARCH_SIZE);
        for (ArmorStand marker : client.level.getEntitiesOfClass(ArmorStand.class, searchBox)) {
            Component custom = marker.getCustomName();
            if (custom == null) {
                continue;
            }
            if (!PestHuntingController.isReelPrompt(
                    PestHuntingController.stripFormatting(custom.getString()))) {
                continue;
            }
            double dx = marker.getX() - pest.getX();
            double dz = marker.getZ() - pest.getZ();
            if (dx * dx + dz * dz <= MARKER_MAX_HORIZONTAL * MARKER_MAX_HORIZONTAL) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPestMob(Entity entity) {
        return entity instanceof Bat || entity instanceof Silverfish;
    }
}
