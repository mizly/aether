package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.modules.visuals.StreamerModeManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class PestTrackerAbility {
    private static final PestTrackerTrail TRAIL = new PestTrackerTrail();
    private static ClientLevel captureLevel;
    private static long nextUseAt;

    private PestTrackerAbility() {
    }

    public static synchronized void onAttack(Minecraft client) {
        if (client.player == null || client.level == null || client.player.isShiftKeyDown()
                || !PestLoadoutHelper.isVacuum(client.player.getMainHandItem())
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN
                || !AetherConfig.PEST_TRACKER_DRAW_ARC.get()
                && !(AetherConfig.USE_PEST_TRACKER_ABILITY.get() && PestDestroyer.isActive()
                && PestDestroyer.getState() == PestDestroyer.State.GET_LOCATION)) return;
        long now = System.currentTimeMillis();
        if (!canUse(now)) return;
        captureLevel = client.level;
        TRAIL.begin(client.player.position(), now);
        nextUseAt = now + 1_100L;
    }

    public static synchronized void tick(Minecraft client) {
        if (client.player == null || client.level != captureLevel
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN
                || !AetherConfig.PEST_TRACKER_DRAW_ARC.get()
                && !(AetherConfig.USE_PEST_TRACKER_ABILITY.get() && PestDestroyer.isActive())) clear();
    }

    public static synchronized void clear() {
        TRAIL.reset();
        captureLevel = null;
    }

    static synchronized boolean canUse(long now) {
        return now >= nextUseAt;
    }

    public static synchronized void onParticlePacket(Minecraft client, ClientboundLevelParticlesPacket packet) {
        if (!client.isSameThread() || client.level != captureLevel || captureLevel == null
                || !matchesPacket(packet)) return;
        TRAIL.add(new Vec3(packet.getX(), packet.getY(), packet.getZ()), System.currentTimeMillis());
    }

    static boolean matchesPacket(ClientboundLevelParticlesPacket packet) {
        return packet != null && packet.getParticle().getType() == ParticleTypes.ANGRY_VILLAGER
                && packet.getCount() == 1 && packet.getMaxSpeed() == 0
                && packet.getXDist() == 0 && packet.getYDist() == 0 && packet.getZDist() == 0;
    }

    static synchronized PestTrackerTrail.Prediction predictionSince(long requestedAt, long now) {
        return TRAIL.belongsTo(requestedAt) ? TRAIL.prediction(now) : null;
    }

    static synchronized boolean isComplete(long requestedAt, long now) {
        return now - requestedAt >= PestTrackerTrail.CAPTURE_TIMEOUT_MS
                || TRAIL.belongsTo(requestedAt) && TRAIL.isComplete(now);
    }

    public static synchronized boolean hasVisibleArc() {
        Minecraft client = Minecraft.getInstance();
        return AetherConfig.PEST_TRACKER_DRAW_ARC.get() && !StreamerModeManager.isEnabled()
                && client.player != null && captureLevel != null && client.level == captureLevel
                && ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN
                && TRAIL.observed(System.currentTimeMillis()).size() > 1;
    }

    public static synchronized void renderWorld() {
        if (!hasVisibleArc()) return;
        long now = System.currentTimeMillis();
        draw(TRAIL.observed(now), 0xFFF06A5D);
        PestTrackerTrail.Prediction prediction = TRAIL.prediction(now);
        if (prediction != null) draw(prediction.extension(), 0xB3FFCD66);
    }

    private static void draw(List<Vec3> points, int color) {
        for (int i = 1; i < points.size(); i++) {
            Gizmos.line(points.get(i - 1), points.get(i), color, 2.0f).setAlwaysOnTop();
        }
    }
}
