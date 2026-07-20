package dev.aether.modules.pest.helpers;

import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Debug helper for identifying particle packet types while left-clicking a vacuum.
 */
public final class VacuumParticleDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("aether");
    private static final Object CAPTURE_LOCK = new Object();
    private static boolean capturing = false;
    private static long captureStartedAt = 0;
    private static long packetCount = 0;
    private static long particleCount = 0;
    private static final Map<String, Integer> packetTypeCounts = new LinkedHashMap<>();

    private VacuumParticleDebug() {
    }

    public static void onClientTick() {
        Minecraft client = Minecraft.getInstance();

        if (client == null || client.player == null || client.options == null) {
            if (capturing) {
                flush("context lost");
            }
            return;
        }

        boolean active = isVacuumLeftClickActive(client);
        if (active && !capturing) {
            beginCapture();
            return;
        }

        if (!active && capturing) {
            flush("left click released");
        }
    }

    public static void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (packet == null) {
            return;
        }

        ParticleOptions options = packet.getParticle();
        String particleId = "unknown";
        if (options != null) {
            Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
            particleId = key != null ? key.toString() : options.getType().toString();
        }

        synchronized (CAPTURE_LOCK) {
            if (!capturing) {
                return;
            }

            packetTypeCounts.merge(particleId, 1, Integer::sum);
            packetCount++;
            particleCount += Math.max(packet.getCount(), 1);
        }
    }

    private static boolean isVacuumLeftClickActive(Minecraft client) {
        if (!client.options.keyAttack.isDown()) {
            return false;
        }

        int selected = ((dev.aether.mixin.AccessorInventory) client.player.getInventory()).getSelected();
        if (selected < 0 || selected >= 9) {
            return false;
        }

        ItemStack held = client.player.getMainHandItem();
        if (held == null || held.isEmpty()) {
            return false;
        }

        String itemName = held.getHoverName().getString()
                .replaceAll("\\u00A7[0-9a-fk-or]", "")
                .trim()
                .toLowerCase();
        return itemName.contains("vacuum");
    }

    private static void beginCapture() {
        synchronized (CAPTURE_LOCK) {
            capturing = true;
            captureStartedAt = System.currentTimeMillis();
            packetCount = 0;
            particleCount = 0;
            packetTypeCounts.clear();
        }
        ClientUtils.sendDebugMessage("VacuumParticleDebug: capture started");
    }

    private static void flush(String reason) {
        long durationMs;
        long capturedPacketCount;
        long capturedParticleCount;
        Map<String, Integer> capturedPacketTypeCounts;

        synchronized (CAPTURE_LOCK) {
            durationMs = Math.max(0, System.currentTimeMillis() - captureStartedAt);
            capturedPacketCount = packetCount;
            capturedParticleCount = particleCount;
            capturedPacketTypeCounts = new LinkedHashMap<>(packetTypeCounts);
            resetLocked();
        }

        if (capturedPacketTypeCounts.isEmpty()) {
            ClientUtils.sendDebugMessage("VacuumParticleDebug: capture ended (" + reason + ") with no particle packets in " + durationMs + "ms");
            LOGGER.info("VacuumParticleDebug: no particle packets captured. reason={}, durationMs={}", reason, durationMs);
            return;
        }

        String summary = capturedPacketTypeCounts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));

        ClientUtils.sendDebugMessage("VacuumParticleDebug: captured " + capturedPacketTypeCounts.size() + " particle type(s), "
                        + capturedPacketCount + " packet(s), " + capturedParticleCount + " spawned particles in " + durationMs + "ms");
        ClientUtils.sendDebugMessage("VacuumParticleDebug: types: " + summary);

        LOGGER.info("VacuumParticleDebug: reason={}, durationMs={}, packetTypes={}, packets={}, particles={}, types=[{}]",
                reason, durationMs, capturedPacketTypeCounts.size(), capturedPacketCount, capturedParticleCount, summary);
    }

    private static void reset() {
        synchronized (CAPTURE_LOCK) {
            resetLocked();
        }
    }

    private static void resetLocked() {
        capturing = false;
        captureStartedAt = 0;
        packetCount = 0;
        particleCount = 0;
        packetTypeCounts.clear();
    }
}
