package dev.aether.modules.pest.helpers;

import dev.aether.modules.pathfinding.execution.FlightMotion;
import dev.aether.modules.rotation.HumanFlick;
import dev.aether.util.GardenPlots;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.random.RandomGenerator;

// an aotv that lands right over, under or beside a pest leaves it where the hover cannot frame it,
// so fly straight back until it would sit on screen instead of letting the tracker spin round it
final class PestCloseRangeBackoff {
    enum Phase {
        IDLE,
        REACT,
        DRIVE,
        BRAKE
    }

    record Retreat(FlightMotion.Input keys, Vec3 direction, double travel) {
    }

    static final FlightMotion.Input RELEASED = new FlightMotion.Input(0, 0);
    // straight back first, since the diagonals pull the pest off the crosshair sideways
    static final List<FlightMotion.Input> RETREAT_KEYS = List.of(
            new FlightMotion.Input(-1, 0),
            new FlightMotion.Input(-1, -1),
            new FlightMotion.Input(-1, 1));
    // the flight controller holds the feet this far above the pest's feet while vacuuming
    static final double HOVER_HEIGHT = 3.0;
    static final double MAX_HORIZONTAL_BLOCKS = 5.0;
    static final double CORRIDOR_MARGIN = 0.15;
    static final int MAX_TICKS = 40;
    static final int ARM_WINDOW_TICKS = 30;
    private static final double VIEW_MARGIN_DEGREES = 6.0;
    private static final double HYSTERESIS_DEGREES = 5.0;
    private static final double MIN_COMFORT_DEGREES = 15.0;
    private static final double MIN_WORTHWHILE_BLOCKS = 0.75;
    private static final double BEHIND_BLOCKS = 0.6;
    // the brake leaves a little coast past the mark, so that stretch has to be clear and on the plot too
    private static final double STOP_OVERSHOOT_BLOCKS = 0.75;
    private static final double PLOT_INSET = 1.0;
    private static final double BRAKE_LEAD_BLOCKS = 0.05;
    private static final double OVERRUN_BLOCKS = 1.0;
    private static final double STOP_SPEED = 0.03;
    private static final double FLIGHT_DRAG = 0.91;
    private static final int MIN_REACT_TICKS = 2;
    private static final int MAX_REACT_TICKS = 5;

    private Phase phase = Phase.IDLE;
    private int targetId = -1;
    private Vec3 start;
    private Vec3 direction;
    private FlightMotion.Input drive;
    private double travel;
    private double required;
    private int startedTick;
    private int reactionTicks;
    private long tiltFlickId;
    private boolean keysHeld;
    private int armedTick = -1;
    private boolean turnRequested;

    void begin(int targetId, Vec3 start, Retreat retreat, double required, int tick, int reactionTicks) {
        this.targetId = targetId;
        this.start = start;
        direction = retreat.direction();
        drive = retreat.keys();
        travel = retreat.travel();
        this.required = required;
        startedTick = tick;
        this.reactionTicks = reactionTicks;
        phase = Phase.REACT;
        keysHeld = true;
        disarm();
    }

    void tilting(long flickId) {
        tiltFlickId = flickId;
    }

    boolean isActive() {
        return phase != Phase.IDLE;
    }

    boolean isFor(int targetId) {
        return isActive() && this.targetId == targetId;
    }

    Phase phase() {
        return phase;
    }

    boolean holdsKeys() {
        return keysHeld;
    }

    // the keys to hold this tick, or null once the back-up is over
    FlightMotion.Input step(int tick, Vec3 position, Vec3 velocity, double horizontal, boolean blocked,
                            double acceleration) {
        int elapsed = tick - startedTick;
        if (phase == Phase.IDLE || elapsed > MAX_TICKS) {
            return null;
        }
        if (phase == Phase.REACT) {
            if (elapsed < reactionTicks) {
                return RELEASED;
            }
            phase = Phase.DRIVE;
        }
        double travelled = (position.x - start.x) * direction.x + (position.z - start.z) * direction.z;
        double speed = velocity.x * direction.x + velocity.z * direction.z;
        if (phase == Phase.DRIVE && (horizontal >= required || blocked || travelled > travel + OVERRUN_BLOCKS
                || shouldBrake(travelled, speed, acceleration, travel))) {
            phase = Phase.BRAKE;
        }
        if (phase == Phase.BRAKE && speed <= STOP_SPEED) {
            return null;
        }
        return phase == Phase.DRIVE ? drive : new FlightMotion.Input(-drive.forward(), -drive.right());
    }

    // the arrival is looked at once, and only waits this long for a pest behind to come round in front
    boolean armExpired(int tick) {
        if (armedTick < 0) {
            armedTick = tick;
        }
        return tick - armedTick > ARM_WINDOW_TICKS;
    }

    // a turn onto the pest is not hesitation, so the wait starts once it has landed
    void holdArm(int tick) {
        armedTick = tick;
    }

    boolean requestTurn() {
        if (turnRequested) {
            return false;
        }
        turnRequested = true;
        return true;
    }

    boolean turnRequested() {
        return turnRequested;
    }

    void disarm() {
        armedTick = -1;
        turnRequested = false;
    }

    // a state change ends the back-up but cannot reach the keys, so they stay marked until taken
    void cancel() {
        if (tiltFlickId != 0L) {
            HumanFlick.cancel(tiltFlickId);
            tiltFlickId = 0L;
        }
        phase = Phase.IDLE;
        targetId = -1;
        start = null;
        direction = null;
        drive = null;
        disarm();
    }

    void reset() {
        cancel();
        keysHeld = false;
    }

    boolean takeAbandonedKeys() {
        if (!keysHeld || phase != Phase.IDLE) {
            return false;
        }
        keysHeld = false;
        return true;
    }

    // a player takes a moment to see the pest is too close before pulling back
    static int reactTicks(RandomGenerator random) {
        return random.nextInt(MIN_REACT_TICKS, MAX_REACT_TICKS + 1);
    }

    // the vacuum hover ends up this far above the pest whichever side it starts on
    static double hoverDrop(double playerEyeHeight, double pestEyeHeight) {
        return HOVER_HEIGHT + playerEyeHeight - pestEyeHeight;
    }

    // hovering, the camera rests on the pest's bucket pitch, so a steeper pest drops off the bottom of the
    // screen whatever the slider says
    static double comfortDegrees(double backUpPitch, double bucketPitch, double verticalFovDegrees) {
        return Math.min(backUpPitch, bucketPitch + verticalFovDegrees / 2.0 - VIEW_MARGIN_DEGREES);
    }

    // aims a few degrees inside the comfort angle so the hover settling cannot tip the pest back over it
    static double requiredHorizontal(double eyeGap, double hoverDrop, double comfortDegrees, double cap) {
        double angle = Math.max(MIN_COMFORT_DEGREES, comfortDegrees - HYSTERESIS_DEGREES);
        return Math.min(cap, Math.max(Math.abs(eyeGap), hoverDrop) / Math.tan(Math.toRadians(angle)));
    }

    static boolean worthBackingUp(double required, double horizontal) {
        return required - horizontal >= MIN_WORTHWHILE_BLOCKS;
    }

    // blocks along the look rather than a bearing, which flips round for a pest almost straight below
    static boolean isBehind(Vec3 offset, float yaw) {
        double angle = Math.toRadians(yaw);
        return -offset.x * Math.sin(angle) + offset.z * Math.cos(angle) < -BEHIND_BLOCKS;
    }

    // how far to fly along a unit direction to leave the pest the required distance away
    static double travelFor(Vec3 offset, Vec3 direction, double required) {
        double along = offset.x * direction.x + offset.z * direction.z;
        double disc = along * along - (offset.x * offset.x + offset.z * offset.z) + required * required;
        return disc <= 0.0 ? 0.0 : Math.max(0.0, along + Math.sqrt(disc));
    }

    // null when every way back is blocked or ends too near the plot edge
    static Retreat chooseRetreat(Vec3 from, Vec3 offset, float yaw, double required, GardenPlots.Bounds plot,
                                 BiPredicate<Vec3, Vec3> corridorClear) {
        for (FlightMotion.Input keys : RETREAT_KEYS) {
            // the sine table rounds sin and cos to neighbouring angles, which leaves this a hair short of unit length
            Vec3 direction = FlightMotion.acceleration(keys, yaw, 1.0).normalize();
            double travel = travelFor(offset, direction, required);
            if (travel < MIN_WORTHWHILE_BLOCKS) {
                continue;
            }
            Vec3 end = from.add(direction.scale(travel + STOP_OVERSHOOT_BLOCKS));
            // leaving the plot makes the plot check teleport us back
            if (plot != null && !plot.contains(end.x, end.z, -PLOT_INSET)) {
                continue;
            }
            if (corridorClear.test(from, end)) {
                return new Retreat(keys, direction, travel);
            }
        }
        return null;
    }

    // letting go of the drive key still coasts, so the brake has to start this far before the mark
    static double brakeDistance(double speed, double acceleration) {
        double distance = 0.0;
        for (int tick = 0; tick < MAX_TICKS && speed > acceleration; tick++) {
            speed -= acceleration;
            distance += speed;
            speed *= FLIGHT_DRAG;
        }
        return distance;
    }

    static boolean shouldBrake(double travelled, double speed, double acceleration, double travel) {
        return travelled + brakeDistance(speed, acceleration) >= travel - BRAKE_LEAD_BLOCKS;
    }

    // where the camera rests on the pest once backed up: the hover pitch over it, looking up at it from below
    static float settledPitch(double eyeGap, double required, float bucketPitch) {
        return eyeGap < 0.0
                ? bucketPitch
                : (float) -Math.toDegrees(Math.atan2(eyeGap, Math.max(required, 1.0e-3)));
    }
}
