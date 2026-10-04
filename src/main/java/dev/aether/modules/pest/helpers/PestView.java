package dev.aether.modules.pest.helpers;

import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

// what the player can actually see: their fov setting, the window's shape and the blocks in the way
final class PestView {
    // a pest right at the edge of the screen is easy to miss, so it only counts once it is a little way in
    static final double SIGHT_MARGIN_DEGREES = 5.0;

    private PestView() {
    }

    static boolean canSee(Minecraft client, Vec3 point) {
        return inView(client, point, SIGHT_MARGIN_DEGREES) && lineOfSight(client, point);
    }

    static double angleFromCrosshair(Minecraft client, Vec3 point) {
        return RotationUtils.angleFromCrosshair(
                client.player.getEyePosition(), client.player.getYRot(), client.player.getXRot(), point);
    }

    static boolean inView(Minecraft client, Vec3 point, double marginDegrees) {
        int width = client.getWindow().getWidth();
        int height = client.getWindow().getHeight();
        // a minimised window reports a height of 0
        double aspect = height > 0 ? (double) width / height : 16.0 / 9.0;
        return RotationUtils.isInView(client.player.getEyePosition(), client.player.getYRot(),
                client.player.getXRot(), point, client.options.fov().get(), aspect, marginDegrees);
    }

    static boolean lineOfSight(Minecraft client, Vec3 point) {
        return ClientUtils.hasLineOfSight(client.player, point);
    }
}
