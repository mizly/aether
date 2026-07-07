package dev.typicalfarmingmacro.modules.visuals;

import com.mojang.blaze3d.platform.InputConstants;
import dev.typicalfarmingmacro.bootstrap.TfmKeybindRegistry;
import dev.typicalfarmingmacro.config.ConfigHelpers;
import dev.typicalfarmingmacro.config.FreelookMode;
import dev.typicalfarmingmacro.config.TfmConfig;
import dev.typicalfarmingmacro.mixin.AccessorKeyMapping;
import dev.typicalfarmingmacro.mixin.MixinMinecraft;
import dev.typicalfarmingmacro.mixin.AccessorWindow;
import dev.typicalfarmingmacro.modules.farming.UngrabMouse;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Freelook / "perspective" camera in the style of Lunar Client and Taunahi: while the
 * bind is held the mouse orbits the camera around the player without touching the player's
 * real yaw/pitch, so the body keeps facing (and moving/farming) in its actual direction.
 * Releasing the bind snaps the view back to the player.
 *
 * <p>Unlike {@link FreecamManager} this never detaches the camera entity, so picking and
 * interactions stay anchored to the player and nothing new is sent to the server - it is
 * purely a client-side view rotation and is anticheat-safe.
 */
public final class FreelookManager {
    private static boolean registered;
    private static boolean active;
    private static boolean keyWasDown;
    private static float yaw;
    private static float pitch;
    private static CameraType previousCameraType;

    private FreelookManager() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(FreelookManager::tick);
    }

    public static boolean isActive() {
        return active;
    }

    public static float getYaw() {
        return yaw;
    }

    public static float getPitch() {
        return pitch;
    }

    /**
     * Consumes a mouse movement delta while freelook is active, accumulating it into the
     * free camera rotation instead of turning the player. Matches vanilla {@code turn}
     * feel (0.15 scale, pitch clamped to [-90, 90]).
     *
     * @return {@code true} if the delta was consumed (freelook active), so the caller
     *     should not turn the player.
     */
    public static boolean turn(double yRot, double xRot) {
        if (!active) {
            return false;
        }
        yaw += (float) (yRot * 0.15);
        pitch += (float) (xRot * 0.15);
        pitch = Mth.clamp(pitch, -90.0f, 90.0f);
        return true;
    }

    private static void tick(Minecraft client) {
        boolean available = isAvailable(client);
        boolean keyDown = available && isKeyDown(client, TfmKeybindRegistry.getFreelookKey());

        if (!available) {
            if (active) {
                disable(client);
            }
            keyWasDown = keyDown;
            return;
        }

        if (ConfigHelpers.getFreelookMode() == FreelookMode.TOGGLE) {
            if (keyDown && !keyWasDown) {
                if (active) {
                    disable(client);
                } else {
                    enable(client);
                }
            }
        } else { // HOLD
            if (keyDown && !active) {
                enable(client);
            } else if (!keyDown && active) {
                disable(client);
            }
        }

        // Keep the block-break suppression counter cleared while orbiting so the macro's
        // continuous attack keeps breaking crops (see clearMissTime / grabMouse note in enable).
        if (active) {
            clearMissTime(client);
        }
        keyWasDown = keyDown;
    }

    /**
     * Resets {@code Minecraft.missTime} to 0. Queued via {@code execute} so it runs after any
     * pending {@code grabMouse()} task (which sets it to 10000) rather than before it.
     */
    private static void clearMissTime(Minecraft client) {
        if (client == null) {
            return;
        }
        client.execute(() -> ((MixinMinecraft) client).typicalfarmingmacro$setMissTime(0));
    }

    /** Whether freelook may run right now (feature on, in-world, no screen, freecam off, streamer mode off). */
    private static boolean isAvailable(Minecraft client) {
        if (client.player == null || client.level == null || client.screen != null) {
            return false;
        }
        if (FreecamManager.isEnabled() || StreamerModeManager.isEnabled()) {
            return false;
        }
        return TfmConfig.FREELOOK_ENABLED.get();
    }

    private static void enable(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.options == null) {
            return;
        }
        active = true;
        yaw = player.getYRot();
        pitch = player.getXRot();
        previousCameraType = client.options.getCameraType();
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        // Keep the cursor grabbed while orbiting, even if "ungrab mouse" is on.
        UngrabMouse.suspendForFreelook();
        // suspendForFreelook re-grabs the cursor when "ungrab mouse" is active, and vanilla
        // grabMouse() sets missTime = 10000 to swallow the re-grab click. The macro holds
        // attack continuously (leftClick=true), which never resets missTime, so block-breaking
        // would stay suppressed. Clear it (both now and every tick below) so the macro keeps
        // hitting the instant freelook opens.
        clearMissTime(client);
    }

    private static void disable(Minecraft client) {
        active = false;
        UngrabMouse.resumeAfterFreelook();
        if (client != null && client.options != null && previousCameraType != null) {
            client.options.setCameraType(previousCameraType);
        }
        previousCameraType = null;
    }

    private static boolean isKeyDown(Minecraft client, KeyMapping mapping) {
        if (mapping == null) {
            return false;
        }
        InputConstants.Key key = ((AccessorKeyMapping) mapping).getKey();
        if (key == null || key == InputConstants.UNKNOWN) {
            return false;
        }
        return switch (key.getType()) {
            case KEYSYM, SCANCODE -> InputConstants.isKeyDown(client.getWindow(), key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(((AccessorWindow) (Object) client.getWindow()).getHandle(), key.getValue()) == GLFW.GLFW_PRESS;
        };
    }
}
