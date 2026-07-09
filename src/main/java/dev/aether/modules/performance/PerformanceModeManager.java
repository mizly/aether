package dev.aether.modules.performance;

import dev.aether.config.AetherConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ParticleStatus;

public class PerformanceModeManager {
    private static int renderDistanceBefore = 0;
    private static int maxFpsBefore = 0;
    private static ParticleStatus particlesBefore = null;
    private static boolean changedRenderDistance = false;
    private static boolean changedMaxFps = false;
    private static boolean changedParticles = false;
    private static boolean enabled = false;
    
    public static boolean isEnabled() {
        return enabled;
    }

    public static void start(Minecraft mc) {
        if (!AetherConfig.PERFORMANCE_MODE.get()) return;
        if (enabled) return;
        
        enabled = true;
        if (mc.options != null) {
            renderDistanceBefore = mc.options.renderDistance().get();
            maxFpsBefore = mc.options.framerateLimit().get();
            particlesBefore = mc.options.particles().get();

            if (AetherConfig.PERFORMANCE_LIMIT_CHUNK_DISTANCE.get()) {
                mc.options.renderDistance().set(AetherConfig.PERFORMANCE_CHUNK_DISTANCE.get());
                changedRenderDistance = true;
            }
            if (AetherConfig.PERFORMANCE_LIMIT_FPS.get()) {
                mc.options.framerateLimit().set(AetherConfig.PERFORMANCE_MODE_MAX_FPS.get());
                changedMaxFps = true;
            }
            if (AetherConfig.PERFORMANCE_DISABLE_PARTICLES.get()) {
                mc.options.particles().set(ParticleStatus.MINIMAL);
                changedParticles = true;
            }
            
            if (mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
        }
    }

    public static void stop(Minecraft mc) {
        if (!enabled) return;
        
        enabled = false;
        if (mc.options != null) {
            // Restore only if they were actually changed by us
            if (changedRenderDistance) {
                mc.options.renderDistance().set(renderDistanceBefore);
            }
            if (changedMaxFps) {
                mc.options.framerateLimit().set(maxFpsBefore);
            }
            if (changedParticles) {
                mc.options.particles().set(particlesBefore);
            }
            
            renderDistanceBefore = 0;
            maxFpsBefore = 0;
            particlesBefore = null;
            changedRenderDistance = false;
            changedMaxFps = false;
            changedParticles = false;
            
            if (mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
        }
    }

    public static boolean isParticlesDisabled() {
        return enabled && AetherConfig.PERFORMANCE_DISABLE_PARTICLES.get();
    }
}
