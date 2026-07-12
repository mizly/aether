package dev.aether.modules.visuals;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import dev.aether.bootstrap.AetherKeybindRegistry;
import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;
import dev.aether.mixin.AccessorKeyMapping;
import dev.aether.mixin.AccessorWindow;
import dev.aether.modules.farming.UngrabMouse;
import dev.aether.util.ClientUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class FreecamManager {
    private static final int OBSERVED_PLAYER_UPDATE_INTERVAL = 2;
    private static final int OBSERVED_PLAYER_INTERPOLATION_STEPS = 3;
    private static final long MACRO_MOVEMENT_GRACE_MS = 200L;
    private static boolean registered;
    private static boolean enabled;
    private static boolean toggleKeyWasDown;
    private static boolean teleportKeyWasDown;

    private static RemotePlayer cameraEntity;
    private static Entity previousCameraEntity;
    private static CameraType previousCameraType;
    private static Vec3 observedRenderPos;
    private static Vec3 observedRenderPrevPos;
    private static Vec3 observedRenderTargetPos;
    private static int observedRenderInterpolationSteps;
    private static int observedRenderTargetTick;
    private static long macroMovementGraceUntilMs;

    private FreecamManager() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(FreecamManager::tick);
        LevelRenderEvents.END_EXTRACTION.register(FreecamManager::appendAnchoredPlayerRenderState);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether the freecam feature is switched on in the UI. Turning this on does not detach
     * the camera by itself - it only lets the freecam keybind toggle the camera on/off, in
     * the same spirit as {@link FreelookManager}. Turning it off also stops any active freecam.
     */
    public static boolean isFeatureEnabled() {
        return AetherConfig.FREECAM_ENABLED.get();
    }

    public static void setFeatureEnabled(boolean shouldEnable) {
        Minecraft client = Minecraft.getInstance();
        AetherConfig.FREECAM_ENABLED.set(shouldEnable);
        AetherConfig.save();
        if (!shouldEnable) {
            setEnabled(false);
        }
    }

    public static boolean turnCamera(double yRot, double xRot) {
        if (!enabled || cameraEntity == null) {
            return false;
        }

        cameraEntity.turn(yRot, xRot);
        syncRotationState(cameraEntity, cameraEntity.getYRot(), cameraEntity.getXRot());
        return true;
    }

    public static boolean shouldSuppressMacroMovementDetection(LocalPlayer player) {
        return player != null && System.currentTimeMillis() < macroMovementGraceUntilMs;
    }

    public static boolean isProgrammaticKeyDown(KeyMapping mapping) {
        Minecraft client = Minecraft.getInstance();
        return mapping != null && mapping.isDown() && !isKeyDown(client, mapping);
    }

    public static void toggle() {
        Minecraft client = Minecraft.getInstance();
        if (!isFeatureEnabled()) {
            if (client != null) {
                ClientUtils.sendMessage("Turn the Freecam module on to use the freecam keybind.", false);
            }
            return;
        }
        setEnabled(!enabled);
    }

    public static void teleportCameraToPlayer() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client == null || client.player == null || cameraEntity == null) {
            return;
        }

        LocalPlayer player = client.player;
        cameraEntity.setOldPosAndRot(player.position(), player.getYRot(), player.getXRot());
        cameraEntity.setPos(player.position());
        syncRotationState(cameraEntity, player.getYRot(), player.getXRot());
        cameraEntity.setDeltaMovement(Vec3.ZERO);
        ClientUtils.sendMessage("Teleported to player!", false);
    }

    public static void setEnabled(boolean shouldEnable) {
        Minecraft client = Minecraft.getInstance();
        if (shouldEnable && StreamerModeManager.isEnabled()) {
            return;
        }
        if (client == null) {
            enabled = false;
            previousCameraType = null;
            macroMovementGraceUntilMs = 0L;
            return;
        }
        if (!client.isSameThread()) {
            client.execute(() -> setEnabled(shouldEnable));
            return;
        }
        if (shouldEnable) {
            enable(client);
            return;
        }
        disable(client, false);
    }

    private static void enable(Minecraft client) {
        if (enabled || client.player == null || client.level == null) {
            return;
        }

        LocalPlayer player = client.player;
        enabled = true;
        previousCameraEntity = client.getCameraEntity();
        previousCameraType = client.options.getCameraType();
        UngrabMouse.suspendForFreecam();

        cameraEntity = createCameraEntity(client.level, player);
        observedRenderPos = player.position();
        observedRenderPrevPos = observedRenderPos;
        observedRenderTargetPos = observedRenderPos;
        observedRenderInterpolationSteps = 0;
        observedRenderTargetTick = player.tickCount;
        startMacroMovementGrace();
        client.setCameraEntity(cameraEntity);
        enforceFirstPerson(client);
        clearLatchedInputState(client, player);
        ClientUtils.sendMessage("Freecam enabled!", false);
    }

    private static void disable(Minecraft client, boolean dueToMissingWorld) {
        if (!enabled && cameraEntity == null) {
            return;
        }

        enabled = false;
        if (client != null && client.player != null) {
            startMacroMovementGrace();
            clearLatchedInputState(client, client.player);
            Entity restore = previousCameraEntity;
            if (restore == null || restore.isRemoved()) {
                restore = client.player;
            }
            client.setCameraEntity(restore);
            if (!dueToMissingWorld) {
                ClientUtils.sendMessage("Freecam disabled!", false);
            }
        }

        restoreCameraType(client);
        UngrabMouse.resumeAfterFreecam();
        cameraEntity = null;
        observedRenderPos = null;
        observedRenderPrevPos = null;
        observedRenderTargetPos = null;
        observedRenderInterpolationSteps = 0;
        observedRenderTargetTick = 0;
        previousCameraEntity = null;
        previousCameraType = null;
    }

    private static void startMacroMovementGrace() {
        macroMovementGraceUntilMs = System.currentTimeMillis() + MACRO_MOVEMENT_GRACE_MS;
    }

    /**
     * Rising-edge poll of the freecam keybinds straight from the physical key state, the same
     * way {@link FreelookManager} reads its bind. This avoids relying on {@code consumeClick()},
     * which never fires when the key mapping ends up in the detached fallback path.
     */
    private static void pollKeybinds(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.screen != null) {
            toggleKeyWasDown = false;
            teleportKeyWasDown = false;
            return;
        }

        boolean toggleDown = isKeyDown(client, AetherKeybindRegistry.getFreecamKey());
        if (toggleDown && !toggleKeyWasDown) {
            toggle();
        }
        toggleKeyWasDown = toggleDown;

        boolean teleportDown = isKeyDown(client, AetherKeybindRegistry.getFreecamTeleportToPlayerKey());
        if (teleportDown && !teleportKeyWasDown && enabled) {
            teleportCameraToPlayer();
        }
        teleportKeyWasDown = teleportDown;
    }

    private static void tick(Minecraft client) {
        pollKeybinds(client);
        if (!enabled) {
            return;
        }
        if (StreamerModeManager.isEnabled()) {
            disable(client, true);
            return;
        }
        if (client.player == null || client.level == null) {
            disable(client, true);
            return;
        }
        if (cameraEntity == null || client.getCameraEntity() != cameraEntity) {
            cameraEntity = createCameraEntity(client.level, client.player);
            client.setCameraEntity(cameraEntity);
        }

        enforceFirstPerson(client);
        moveCamera(client);
        updateObservedRenderState(client.player);
    }

    private static void appendAnchoredPlayerRenderState(LevelExtractionContext context) {
        if (!enabled || StreamerModeManager.isEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || context.level() != player.level()) {
            return;
        }
        if (isPlayerAlreadyQueued(context, player.getId())) {
            return;
        }

        float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(!player.level().tickRateManager().isEntityFrozen(player));
        EntityRenderState renderState = client.getEntityRenderDispatcher().extractEntity(player, partialTick);
        if (observedRenderPos != null && observedRenderPrevPos != null) {
            renderState.x = Mth.lerp((double)partialTick, observedRenderPrevPos.x, observedRenderPos.x);
            renderState.y = Mth.lerp((double)partialTick, observedRenderPrevPos.y, observedRenderPos.y);
            renderState.z = Mth.lerp((double)partialTick, observedRenderPrevPos.z, observedRenderPos.z);
        }
        context.levelState().entityRenderStates.add(renderState);
        if (renderState.appearsGlowing()) {
            context.levelState().haveGlowingEntities = true;
        }
    }

    private static RemotePlayer createCameraEntity(ClientLevel level, LocalPlayer player) {
        GameProfile profile = new GameProfile(player.getUUID(), player.getName().getString());
        RemotePlayer camera = new RemotePlayer(level, profile);
        camera.setInvisible(true);
        camera.noPhysics = true;
        camera.setNoGravity(true);
        camera.setOldPosAndRot(player.position(), player.getYRot(), player.getXRot());
        camera.setPos(player.position());
        syncRotationState(camera, player.getYRot(), player.getXRot());
        camera.setDeltaMovement(Vec3.ZERO);
        return camera;
    }

    private static boolean isPlayerAlreadyQueued(LevelExtractionContext context, int playerId) {
        for (EntityRenderState renderState : context.levelState().entityRenderStates) {
            if (renderState instanceof AvatarRenderState avatarRenderState && avatarRenderState.id == playerId) {
                return true;
            }
        }
        return false;
    }

    private static void clearLatchedInputState(Minecraft client, LocalPlayer player) {
        // Leave movement to the macro while it is running - releasing it here stutters the
        // walk for a tick on each toggle, which directly drops mining CPS. Attack/use are
        // still released so the reapply path restores them cleanly (guarding them broke LC).
        boolean macroRunning = MacroStateManager.isMacroRunning();
        if (client.options != null) {
            if (!macroRunning) {
                ClientUtils.setKeyMappingState(client.options.keyUp, false);
                ClientUtils.setKeyMappingState(client.options.keyDown, false);
                ClientUtils.setKeyMappingState(client.options.keyLeft, false);
                ClientUtils.setKeyMappingState(client.options.keyRight, false);
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
                ClientUtils.setKeyMappingState(client.options.keyShift, false);
                ClientUtils.setKeyMappingState(client.options.keySprint, false);
            }
            ClientUtils.setKeyMappingState(client.options.keyAttack, false);
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
        }
        if (macroRunning) {
            return;
        }
        // Do NOT force horizontal velocity to zero. The real player keeps ticking while
        // the camera is detached, so once the movement keys are released above, friction
        // decelerates it exactly like the server does. Snapping velocity to zero on the
        // client (even when grounded) stops it dead in a single tick while the server keeps
        // decelerating over several, and that divergence is what trips the "simulation"
        // check when entering / leaving freecam. Let friction do the work on both sides.
        player.setJumping(false);
        player.setShiftKeyDown(false);
        player.setSprinting(false);
        player.input.keyPresses = new Input(false, false, false, false, false, false, false);
    }

    private static void updateObservedRenderState(LocalPlayer player) {
        Vec3 currentPos = player.position();
        if (observedRenderPos == null || observedRenderPrevPos == null || observedRenderTargetPos == null) {
            observedRenderPos = currentPos;
            observedRenderPrevPos = currentPos;
            observedRenderTargetPos = currentPos;
            observedRenderInterpolationSteps = 0;
            observedRenderTargetTick = player.tickCount;
            return;
        }

        observedRenderPrevPos = observedRenderPos;
        if (observedRenderTargetPos.distanceToSqr(currentPos) > 1.0E-6
            && player.tickCount - observedRenderTargetTick >= OBSERVED_PLAYER_UPDATE_INTERVAL) {
            observedRenderTargetPos = currentPos;
            observedRenderInterpolationSteps = OBSERVED_PLAYER_INTERPOLATION_STEPS;
            observedRenderTargetTick = player.tickCount;
        }

        if (observedRenderInterpolationSteps > 0) {
            double progress = 1.0 / observedRenderInterpolationSteps;
            observedRenderPos = new Vec3(
                Mth.lerp(progress, observedRenderPos.x, observedRenderTargetPos.x),
                Mth.lerp(progress, observedRenderPos.y, observedRenderTargetPos.y),
                Mth.lerp(progress, observedRenderPos.z, observedRenderTargetPos.z)
            );
            observedRenderInterpolationSteps--;
            return;
        }

        observedRenderPos = observedRenderTargetPos;
    }

    private static void moveCamera(Minecraft client) {
        if (cameraEntity == null) {
            return;
        }

        float speed = AetherConfig.FREECAM_SPEED.get();
        if (isKeyDown(client, client.options.keySprint)) {
            speed *= 2.0f;
        }

        Vec3 look = cameraEntity.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0, look.z);
        if (forward.lengthSqr() < 1.0E-6) {
            double yawRad = Math.toRadians(cameraEntity.getYRot());
            forward = new Vec3(-Math.sin(yawRad), 0.0, Math.cos(yawRad));
        }
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

        Vec3 motion = Vec3.ZERO;
        if (isKeyDown(client, client.options.keyUp)) {
            motion = motion.add(forward);
        }
        if (isKeyDown(client, client.options.keyDown)) {
            motion = motion.subtract(forward);
        }
        if (isKeyDown(client, client.options.keyLeft)) {
            motion = motion.subtract(right);
        }
        if (isKeyDown(client, client.options.keyRight)) {
            motion = motion.add(right);
        }
        if (isKeyDown(client, client.options.keyJump)) {
            motion = motion.add(0.0, 1.0, 0.0);
        }
        if (isKeyDown(client, client.options.keyShift)) {
            motion = motion.add(0.0, -1.0, 0.0);
        }

        cameraEntity.setOldPosAndRot(cameraEntity.position(), cameraEntity.getYRot(), cameraEntity.getXRot());
        if (motion.lengthSqr() > 1.0E-6) {
            cameraEntity.setPos(cameraEntity.position().add(motion.normalize().scale(speed)));
        }
        syncRotationState(cameraEntity, cameraEntity.getYRot(), cameraEntity.getXRot());
        cameraEntity.setDeltaMovement(Vec3.ZERO);
    }

    private static void syncRotationState(LivingEntity entity, float yaw, float pitch) {
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.yRotO = yaw;
        entity.xRotO = pitch;
        entity.setYHeadRot(yaw);
        entity.yHeadRotO = yaw;
        entity.setYBodyRot(yaw);
        entity.yBodyRotO = yaw;
    }

    private static boolean isKeyDown(Minecraft client, KeyMapping mapping) {
        InputConstants.Key key = ((AccessorKeyMapping) mapping).getKey();
        if (key == null || key == InputConstants.UNKNOWN) {
            return false;
        }
        return switch (key.getType()) {
            case KEYSYM, SCANCODE -> InputConstants.isKeyDown(client.getWindow(), key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(((AccessorWindow) (Object) client.getWindow()).getHandle(), key.getValue()) == GLFW.GLFW_PRESS;
        };
    }

    private static void enforceFirstPerson(Minecraft client) {
        if (client.options.getCameraType() != CameraType.FIRST_PERSON) {
            client.options.setCameraType(CameraType.FIRST_PERSON);
        }
    }

    private static void restoreCameraType(Minecraft client) {
        if (client == null || previousCameraType == null) {
            return;
        }
        client.options.setCameraType(previousCameraType);
    }
}
