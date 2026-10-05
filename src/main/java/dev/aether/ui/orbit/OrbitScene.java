package dev.aether.ui.orbit;

import dev.aether.config.AetherConfig;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;

// where the ring is built: the saved garden spot when it is chosen and its chunks are loaded on this client,
// otherwise around the player. only the camera goes there; the player never moves
record OrbitScene(Vector3d anchor, float yaw) {
    static OrbitScene spot(OrbitIsland island) {
        if (AetherConfig.ORBIT_SCENE.get() != 1 || !AetherConfig.ORBIT_SPOT_SET.get() || island != OrbitIsland.GARDEN) {
            return null;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        int x = (int) Math.floor(AetherConfig.ORBIT_SPOT_X.get());
        int z = (int) Math.floor(AetherConfig.ORBIT_SPOT_Z.get());
        // the spot is built from the live world when it is loaded around it, else from the copy taken last time
        if (GardenRecorder.spot() == null && !level.hasChunksAt(x - 24, z - 24, x + 24, z + 24)) return null;
        Vector3d anchor = new Vector3d(AetherConfig.ORBIT_SPOT_X.get(),
                AetherConfig.ORBIT_SPOT_Y.get(), AetherConfig.ORBIT_SPOT_Z.get());
        return new OrbitScene(anchor, AetherConfig.ORBIT_SPOT_YAW.get());
    }
}
