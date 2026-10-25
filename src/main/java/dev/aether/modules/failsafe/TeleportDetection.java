package dev.aether.modules.failsafe;

import dev.aether.macro.MacroState;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Set;

final class TeleportDetection {
    private TeleportDetection() {}

    static boolean shouldMonitor(MacroState.State state, boolean onlyDuringFarming,
            boolean farming, boolean pestBusy, boolean inGarden) {
        if (state == MacroState.State.OFF || state == MacroState.State.RECOVERING) return false;
        return !onlyDuringFarming || (state == MacroState.State.FARMING && farming && !pestBusy && inGarden);
    }

    static Vec3 displacement(PositionMoveRotation current, PositionMoveRotation change, Set<Relative> relatives) {
        return PositionMoveRotation.calculateAbsolute(current, change, relatives).position().subtract(current.position());
    }

    static boolean isUnexpected(Vec3 displacement, double horizontalLimit, double verticalLimit, String heldItem) {
        if (!Double.isFinite(displacement.x) || !Double.isFinite(displacement.y) || !Double.isFinite(displacement.z)
                || displacement.lengthSqr() == 0.0) {
            return false;
        }
        if (Math.abs(displacement.y) >= verticalLimit) {
            return true;
        }
        if (Math.hypot(displacement.x, displacement.z) <= horizontalLimit) {
            return false;
        }
        return !heldItem.contains("Aspect of the") && !heldItem.contains("Hyperion")
                && !heldItem.contains("Scylla") && !heldItem.contains("Valk") && !heldItem.contains("Astr");
    }

    static boolean isTeleportCommand(String command) {
        if (command == null) return false;
        String normalized = command.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        String verb = normalized.split("\\s+", 2)[0];
        return switch (verb) {
            case "warp", "warpgarden", "garden", "plottp", "tptoplot", "hub", "lobby", "l", "skyblock", "visit" -> true;
            default -> false;
        };
    }

    static boolean isSettling(long now, long lastTeleport, long settleMs) {
        return lastTeleport != Long.MIN_VALUE && now >= lastTeleport && now - lastTeleport < settleMs;
    }
}
