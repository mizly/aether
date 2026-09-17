package dev.aether.renderer;

import dev.aether.config.AetherConfig;
import dev.aether.modules.visuals.FreecamManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class HaloRenderer {
    private final HaloMesh model = new HaloMesh();
    private final Matrix4f head = new Matrix4f();
    private float phase;
    private long lastFrame;

    public static boolean visible(Minecraft client) {
        return AetherConfig.HALO_ENABLED.get() && client.player != null && client.level != null
                && client.player.isAlive() && !client.player.isSpectator()
                && !client.player.isInvisible() && !client.player.isSleeping()
                && (FreecamManager.isEnabled() || !client.options.getCameraType().isFirstPerson());
    }

    public void append(CosmeticMesh mesh, Minecraft client, Vec3 camera, long now) {
        float dt = lastFrame == 0 ? 0f : Math.clamp((now - lastFrame) / 1_000_000_000f, 0f, 0.1f);
        lastFrame = now;
        float speed = AetherConfig.HALO_SPEED.get();
        phase = speed == 0f ? 0f : (phase + dt * speed * 0.9f) % (float) (Math.PI * 40);
        var player = client.player;
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 position = player.getEyePosition(partial);
        float scale = player.getScale();
        float yaw = Mth.rotLerp(partial, player.yHeadRotO, player.yHeadRot);
        head.identity().translate((float) (position.x - camera.x), (float) (position.y - camera.y),
                (float) (position.z - camera.z)).rotateY((float) Math.toRadians(-yaw)).scale(scale);
        float swim = player.isFallFlying() ? 1f : player.getSwimAmount(partial);
        head.translate(0, 0, swim * 0.65f);
        float bob = (float) Math.sin(phase) * 0.025f;
        head.translate(0, 0.22f + AetherConfig.HALO_HEIGHT.get() + bob, 0)
                .rotateX((float) Math.toRadians(AetherConfig.HALO_TILT.get()))
                .rotateZ((float) Math.toRadians(AetherConfig.HALO_TILT.get() * 0.35f))
                .scale(AetherConfig.HALO_SCALE.get());
        model.append(mesh, head, AetherConfig.HALO_STYLE.get(), phase,
                AetherConfig.HALO_COLOR.get(), AetherConfig.HALO_GLOW.get());
    }
}
