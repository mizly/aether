package dev.aether.modules.pathfinding.harness;

import dev.aether.modules.pathfinding.execution.FlightAim;
import dev.aether.modules.pathfinding.execution.FlightGuidance;
import dev.aether.modules.pathfinding.execution.FlightMotion;
import dev.aether.modules.pathfinding.execution.FlightView;
import dev.aether.modules.pathfinding.rotation.AngleUtils;
import dev.aether.modules.pathfinding.rotation.EasingType;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.List;

// a creative-flight player on a voxel grid, ticked by whatever FlightGuidance asks for
public final class FlightSim implements FlightView {
    public static final double FLYING_SPEED = 0.05;
    public static final double VERTICAL_IMPULSE = FLYING_SPEED * 3.0;
    private static final double HORIZONTAL_DRAG = 0.91;
    private static final double VERTICAL_DRAG = 0.6;
    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.8;
    private static final double EYE_HEIGHT = 1.62;
    // minecraft quantises mouse deltas; 0.15 is the value for the default sensitivity
    private static final double GCD = 0.15;

    private final BlockWorld world;

    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private float yaw;
    private float pitch;
    private boolean sprinting;
    private long now = 1_000_000L;

    private boolean turning;
    private float turnStartYaw;
    private float turnStartPitch;
    private float turnTargetYaw;
    private float turnTargetPitch;
    private long turnStart;
    private long turnEnd;

    private boolean blocked;

    public FlightSim(BlockWorld world, Vec3 position, float yaw) {
        this.world = world;
        this.position = position;
        this.yaw = yaw;
    }

    public void advance(FlightGuidance.Command command) {
        if (command.aim() != null) {
            FlightAim.Plan plan = FlightAim.plan(yaw, pitch, command.aim(), turning, turnTargetYaw, turnTargetPitch);
            if (plan != null) {
                turnStartYaw = yaw;
                turnStartPitch = pitch;
                turnTargetYaw = plan.rotation().yaw;
                turnTargetPitch = plan.rotation().pitch;
                turnStart = now;
                turnEnd = now + plan.durationMs();
                turning = true;
            }
        }
        updateTurn();

        sprinting = command.sprint();
        Vec3 requested = velocity
                .add(FlightMotion.acceleration(command.horizontal(), yaw, FLYING_SPEED * (sprinting ? 2.0 : 1.0)))
                .add(0.0, command.vertical() * VERTICAL_IMPULSE, 0.0);

        Vec3 moved = collide(requested);
        blocked = moved.distanceToSqr(requested) > 1.0e-12;
        position = position.add(moved);
        velocity = new Vec3(
                Math.abs(moved.x - requested.x) > 1.0e-9 ? 0.0 : requested.x,
                Math.abs(moved.y - requested.y) > 1.0e-9 ? 0.0 : requested.y,
                Math.abs(moved.z - requested.z) > 1.0e-9 ? 0.0 : requested.z)
                .multiply(HORIZONTAL_DRAG, VERTICAL_DRAG, HORIZONTAL_DRAG);
        now += 50;
    }

    // released keys still coast, which is what the player does while a replan is in flight
    public void coast() {
        advance(new FlightGuidance.Command(FlightGuidance.Status.STEER, FlightGuidance.Mode.NONE,
                new FlightMotion.Input(0, 0), 0, false, null, null));
    }

    public void stopTurning() {
        turning = false;
    }

    public boolean blocked() {
        return blocked;
    }

    private void updateTurn() {
        if (!turning) {
            return;
        }
        float progress = turnEnd <= turnStart ? 1.0f
                : Mth.clamp((now - turnStart) / (float) (turnEnd - turnStart), 0.0f, 1.0f);
        float yawDelta = AngleUtils.normalizeAngle(turnTargetYaw - turnStartYaw);
        yaw = quantise(EasingType.EASE_IN_OUT_CUBIC.apply(turnStartYaw, turnStartYaw + yawDelta, progress), yaw);
        pitch = quantise(EasingType.EASE_IN_OUT_CUBIC.apply(turnStartPitch, turnTargetPitch, progress), pitch);
    }

    private static float quantise(float wanted, float current) {
        double delta = AngleUtils.getRotationDelta(current, wanted);
        return (float) (current + Math.round(delta / GCD) * GCD);
    }

    private Vec3 collide(Vec3 movement) {
        AABB box = bodyAt(position);
        List<AABB> obstacles = world.collisions(box.expandTowards(movement).inflate(0.5));
        if (obstacles.isEmpty()) {
            return movement;
        }

        double y = movement.y;
        for (AABB obstacle : obstacles) {
            y = Shapes.create(obstacle).collide(Direction.Axis.Y, box, y);
        }
        box = box.move(0.0, y, 0.0);

        double x = movement.x;
        double z = movement.z;
        if (Math.abs(movement.x) >= Math.abs(movement.z)) {
            for (AABB obstacle : obstacles) {
                x = Shapes.create(obstacle).collide(Direction.Axis.X, box, x);
            }
            box = box.move(x, 0.0, 0.0);
            for (AABB obstacle : obstacles) {
                z = Shapes.create(obstacle).collide(Direction.Axis.Z, box, z);
            }
        } else {
            for (AABB obstacle : obstacles) {
                z = Shapes.create(obstacle).collide(Direction.Axis.Z, box, z);
            }
            box = box.move(0.0, 0.0, z);
            for (AABB obstacle : obstacles) {
                x = Shapes.create(obstacle).collide(Direction.Axis.X, box, x);
            }
        }
        return new Vec3(x, y, z);
    }

    @Override
    public Vec3 position() {
        return position;
    }

    @Override
    public Vec3 eyePosition() {
        return position.add(0.0, EYE_HEIGHT, 0.0);
    }

    @Override
    public Vec3 velocity() {
        return velocity;
    }

    @Override
    public float yaw() {
        return yaw;
    }

    @Override
    public float pitch() {
        return pitch;
    }

    @Override
    public double flyingSpeed() {
        return FLYING_SPEED;
    }

    @Override
    public boolean sprinting() {
        return sprinting;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    @Override
    public AABB bodyAt(Vec3 feet) {
        return new AABB(feet.x - WIDTH / 2, feet.y, feet.z - WIDTH / 2,
                feet.x + WIDTH / 2, feet.y + HEIGHT, feet.z + WIDTH / 2);
    }

    @Override
    public Iterable<AABB> collisions(AABB search) {
        return world.collisions(search);
    }
}
