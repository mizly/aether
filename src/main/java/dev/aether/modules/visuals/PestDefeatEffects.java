package dev.aether.modules.visuals;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.modules.pest.helpers.PestTargetTracker;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PestDefeatEffects {
    private static final DefeatEffectPool EFFECTS = new DefeatEffectPool();
    private static final Map<UUID, Tracked> tracked = new LinkedHashMap<>();
    private static ClientLevel level;

    private PestDefeatEffects() {}

    public static void tick(Minecraft client) {
        if (client.level != level) {
            clear();
            level = client.level;
        }
        if (!enabled(client)) {
            clear();
            return;
        }
        long now = System.nanoTime();
        EFFECTS.prune(now);
        if (ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN) {
            tracked.clear();
            return;
        }
        for (Entity pest : PestTargetTracker.getLoadedPestMobs(client)) {
            if (client.player.distanceToSqr(pest) > 48 * 48) continue;
            Tracked previous = tracked.get(pest.getUUID());
            if (previous == null) {
                if (tracked.size() >= 64) continue;
                tracked.put(pest.getUUID(), new Tracked(pest, now));
            } else {
                previous.lastSeen = now;
            }
        }
        for (Tracked pest : tracked.values()) {
            // Ordinary unloads and teleports are not defeats. Keep references briefly for late catch chat.
            if (!pest.defeated && (pest.entity.getRemovalReason() == Entity.RemovalReason.KILLED
                    || pest.entity instanceof LivingEntity living && living.isDeadOrDying())) {
                onDefeat(pest.entity);
                pest.defeated = true;
            }
        }
        tracked.values().removeIf(pest -> now - pest.lastSeen > 1_000_000_000L);
    }

    public static void onDefeat(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        if (entity == null || client == null) return;
        if (!client.isSameThread()) {
            client.execute(() -> onDefeat(entity));
            return;
        }
        if (!enabled(client) || entity.level() != client.level
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN
                || client.player.distanceToSqr(entity) > 48 * 48) return;
        if (level != client.level) { clear(); level = client.level; }
        spawn(entity.getUUID(), entity.getBoundingBox().getCenter());
    }

    public static void onCatchChat() {
        Minecraft client = Minecraft.getInstance();
        // The hunting controller supplies the exact target when automation is catching a pest.
        if (PestDestroyer.isCatchInProgress() || !enabled(client) || level != client.level
                || ClientUtils.getCurrentLocation() != MacroState.Location.GARDEN) return;
        Entity candidate = null;
        double best = Double.MAX_VALUE;
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1f);
        long now = System.nanoTime();
        for (Tracked pest : tracked.values()) {
            Entity entity = pest.entity;
            double distance = client.player.distanceToSqr(entity);
            if (distance > 12 * 12 || now - pest.lastSeen > 750_000_000L) continue;
            boolean attached = entity instanceof Leashable leash && leash.getLeashHolder() == client.player;
            double alignment = entity.getBoundingBox().getCenter().subtract(eye).normalize().dot(look);
            if (!attached && !entity.isRemoved() && alignment < 0.92) continue;
            double score = distance + (attached ? -200 : 0) + (entity.isRemoved() ? -100 : 0);
            if (score < best) { candidate = entity; best = score; }
        }
        if (candidate != null) onDefeat(candidate);
    }

    public static void preview() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || StreamerModeManager.isEnabled()) return;
        AetherConfig.PEST_DEFEAT_EFFECTS.set(true);
        AetherConfig.save();
        if (level != client.level) { clear(); level = client.level; }
        client.setScreen(null);
        spawn(UUID.randomUUID(), client.player.getEyePosition().add(client.player.getViewVector(1f).scale(3)));
    }

    private static void spawn(UUID id, Vec3 position) {
        EFFECTS.spawn(id, position, AetherConfig.PEST_DEFEAT_STYLE.get(), AetherConfig.PEST_DEFEAT_SCALE.get(),
                AetherConfig.PEST_DEFEAT_PARTICLES.get(), System.nanoTime());
    }

    private static boolean enabled(Minecraft client) {
        return client != null && client.player != null && client.level != null
                && AetherConfig.PEST_DEFEAT_EFFECTS.get() && !StreamerModeManager.isEnabled();
    }

    public static DefeatEffectPool effects() { return EFFECTS; }
    public static boolean hasActiveEffects(Minecraft client) {
        return enabled(client) && level == client.level && !EFFECTS.active().isEmpty();
    }
    public static void clear() { EFFECTS.clear(); tracked.clear(); }

    private static final class Tracked {
        private final Entity entity;
        private long lastSeen;
        private boolean defeated;
        private Tracked(Entity entity, long now) { this.entity = entity; lastSeen = now; }
    }
}
