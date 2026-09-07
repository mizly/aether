package dev.aether.renderer;

import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.FreecamManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class DragonWingsRenderer {
    private final DragonWingMesh model = new DragonWingMesh();
    private final Matrix4f body = new Matrix4f();
    private float phase;
    private float amplitude = 0.16f;
    private float fold = 0.25f;
    private long lastFrame;

    public static boolean visible(Minecraft client) {
        return AetherConfig.DRAGON_WINGS_ENABLED.get() && client.player != null && client.level != null
                && !client.player.isSpectator() && !client.player.isInvisible() && !client.player.isSleeping()
                && (FreecamManager.isEnabled() || !client.options.getCameraType().isFirstPerson());
    }

    public void append(CosmeticMesh mesh, Minecraft client, Vec3 camera, long now) {
        float dt = lastFrame == 0 ? 0f : Math.clamp((now - lastFrame) / 1_000_000_000f, 0f, 0.1f);
        lastFrame = now;
        var player = client.player;
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float movement = (float) Math.clamp(player.getDeltaMovement().horizontalDistance() * 6, 0, 1);
        boolean gliding = player.isFallFlying();
        boolean flying = player.getAbilities().flying;
        float targetAmplitude = gliding ? 0.045f : flying ? 0.42f : 0.16f + movement * 0.14f;
        float targetFold = player.isCrouching() ? 0.75f : gliding ? 0.06f : 0.25f;
        float follow = 1f - (float) Math.exp(-6f * dt);
        amplitude += (targetAmplitude - amplitude) * follow;
        fold += (targetFold - fold) * follow;
        phase = (phase + dt * AetherConfig.DRAGON_WINGS_SPEED.get() * (gliding ? 1.8f : 3.2f + movement))
                % (float) (Math.PI * 2);
        Vec3 position = player.getPosition(partial);
        float yaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot);
        body.identity().translate((float) (position.x - camera.x), (float) (position.y - camera.y),
                (float) (position.z - camera.z)).rotateY((float) Math.toRadians(-yaw));
        if (gliding || player.isVisuallySwimming()) {
            body.translate(0, 0.9f, 0).rotateX((float) Math.toRadians(-90 - player.getXRot())).translate(0, -0.9f, 0);
        } else if (player.isCrouching()) {
            body.translate(0, -0.2f, 0).rotateX(-0.18f);
        }
        float scale = AetherConfig.DRAGON_WINGS_SCALE.get();
        body.translate(0, 1.38f, -0.18f).scale(scale).translate(0, -1.38f, 0.18f);
        model.append(mesh, body, phase, amplitude, fold, AetherConfig.DRAGON_WINGS_COLOR.get(),
                AetherConfig.DRAGON_WINGS_GLOW.get(), AetherConfig.DRAGON_WINGS_WIREFRAME.get());
    }
}
