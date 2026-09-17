package dev.aether.modules.pathfinding.execution;

import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.rotation.AngleUtils;
import dev.aether.modules.pathfinding.rotation.Rotation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// the per-tick steering brain behind FlyExecutor, free of any Minecraft singletons so it can be simulated
public final class FlightGuidance {

    public static final double REACH = 1.5;
    public static final double CORNER_REACH = 0.1;
    public static final double STOP_THRESH = 0.5;
    public static final double MAX_SPEED = 0.6;
    public static final double SPRINT_SPEED = 1.0;

    private static final long STUCK_CLIMB_MS = 1500;
    private static final long STUCK_ABORT_MS = 3000;
    private static final long DECELERATE_TIMEOUT_MS = 650L;
    private static final int REJOIN_SAMPLES = 4;
    // holding nearer the planned height than this just oscillates: one flight impulse drifts 0.375
    static final double CRUISE_HEIGHT_TOLERANCE = 0.25;
    // levelling back onto a segment is worth one deliberate overshoot, so it may hunt where cruise may not
    private static final double LEVEL_OFF_TOLERANCE = 0.1;

    public enum State {
        IDLE, FLYING, DECELERATING, FINISHED
    }

    public enum Status {
        HOLD, STEER, REPATH, ARRIVED
    }

    // which steering branch produced this tick, surfaced for the debug overlay and the flight simulator
    public enum Mode {
        NONE, BRAKE, ARRIVE, CORNER, CRUISE, REJOIN
    }

    private record Steering(FlightMotion.Input horizontal, int vertical) {
    }

    public record Command(Status status, Mode mode, FlightMotion.Input horizontal, int vertical, boolean sprint,
                          Rotation aim, String note) {
        static Command hold() {
            return new Command(Status.HOLD, Mode.NONE, new FlightMotion.Input(0, 0), 0, false, null, null);
        }

        static Command repath(String note) {
            return new Command(Status.REPATH, Mode.NONE, new FlightMotion.Input(0, 0), 0, false, null, note);
        }

        static Command arrived() {
            return new Command(Status.ARRIVED, Mode.NONE, new FlightMotion.Input(0, 0), 0, false, null, null);
        }
    }

    private final FlightProgressTracker progressTracker = new FlightProgressTracker();

    private State state = State.IDLE;
    private List<Node> path;
    private int wpIndex;
    private Vec3 goal = Vec3.ZERO;
    private long decelStartTime;

    private boolean usePitchControl;
    private float targetPitch;
    private Vec3 lookTarget;
    private double finalWaypointReach = REACH;
    private double goalStopThreshold = STOP_THRESH;
    private double brakingLookaheadTicks = 2.0;

    public void start(List<Node> path, int goalX, int goalY, int goalZ) {
        this.path = new ArrayList<>(path);
        this.goal = waypoint(goalX, goalY, goalZ);
        this.wpIndex = 0;
        this.decelStartTime = 0;
        this.progressTracker.reset();
        this.usePitchControl = false;
        this.lookTarget = null;
        this.finalWaypointReach = REACH;
        this.goalStopThreshold = STOP_THRESH;
        this.state = State.FLYING;
    }

    public void stop() {
        state = State.IDLE;
    }

    public State state() {
        return state;
    }

    public int waypointIndex() {
        return wpIndex;
    }

    public List<Node> path() {
        return path;
    }

    public void setPitchControl(float pitch) {
        this.usePitchControl = true;
        this.targetPitch = pitch;
        this.lookTarget = null;
    }

    public void setLookTargetRotation(Vec3 lookTarget) {
        this.lookTarget = lookTarget;
        this.usePitchControl = false;
    }

    public void setPreciseGoalTolerance(double tolerance) {
        double clamped = Math.max(0.01, tolerance);
        this.finalWaypointReach = clamped;
        this.goalStopThreshold = clamped;
    }

    public void setBrakingLookaheadTicks(double ticks) {
        this.brakingLookaheadTicks = ticks;
    }

    public static Vec3 waypoint(int x, int y, int z) {
        return new Vec3(x + 0.5, y + 0.15, z + 0.5);
    }

    public static Vec3 waypoint(Node node) {
        return waypoint(node.position.flooredX(), node.position.flooredY(), node.position.flooredZ());
    }

    public Command tick(FlightView view) {
        if (state == State.DECELERATING) {
            return tickDecelerate(view);
        }
        if (state != State.FLYING) {
            return Command.hold();
        }
        if (path == null || path.isEmpty()) {
            state = State.FINISHED;
            return Command.arrived();
        }

        Vec3 pos = view.position();

        // momentum can carry us past a node while the async search is still running, so pick up at
        // the closest early waypoint we can see rather than flying back to a stale start block
        skipStaleStartingWaypoints(view, pos);

        Vec3 rejoin = null;
        boolean preserveWaypoint = false;
        while (wpIndex < path.size()) {
            Node wp = path.get(wpIndex);
            if (!hasClearFlightLine(view, pos, wp)) {
                // drifting off a validated segment blocks the straight line to its end, but the
                // segment itself is still clear, so steer back onto it instead of binning the route
                rejoin = rejoinPoint(view, pos);
                if (rejoin == null) {
                    state = State.IDLE;
                    return Command.repath("Fly route obstructed. Requesting a new path.");
                }
                break;
            }
            Vec3 target = waypoint(wp);
            double distSq = pos.distanceToSqr(target);

            if (wpIndex >= path.size() - 1) {
                if (distSq <= finalWaypointReach * finalWaypointReach * 1.5) {
                    wpIndex++;
                    break;
                }
            }

            double waypointReach = wpIndex == path.size() - 1 ? finalWaypointReach : REACH;
            boolean reached = distSq <= waypointReach * waypointReach;
            if (!reached && wpIndex > 0 && wpIndex < path.size() - 1) {
                Vec3 toWp = target.subtract(pos);
                Vec3 pathDir = target.subtract(waypoint(path.get(wpIndex - 1))).normalize();
                if (toWp.dot(pathDir) < 0) {
                    reached = true;
                }
            }

            if (reached && wpIndex + 1 < path.size()
                    && !hasClearFlightLine(view, pos, path.get(wpIndex + 1))) {
                if (distSq <= CORNER_REACH * CORNER_REACH) {
                    state = State.IDLE;
                    return Command.repath("Next fly waypoint obstructed. Requesting a new path.");
                }
                preserveWaypoint = true;
                break;
            }

            if (reached) {
                wpIndex++;
            } else {
                break;
            }
        }

        double distToGoal = pos.distanceTo(goal);

        if (wpIndex >= path.size() - 1 && shouldStopNow(view, pos)) {
            return beginDecelerate(view);
        }
        if (wpIndex >= path.size()) {
            return beginDecelerate(view);
        }

        Vec3 waypointTarget = waypoint(path.get(wpIndex));
        Vec3 target = rejoin != null ? rejoin : waypointTarget;
        Vec3 next = rejoin != null ? waypointTarget
                : wpIndex + 1 < path.size() ? waypoint(path.get(wpIndex + 1)) : null;
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double dyWp = target.y;

        Rotation aim = aim(view, dx, dz, distToGoal);

        Vec3 velocity = view.velocity();
        double brakingRange = 3.0 + velocity.horizontalDistance()
                * FlightMotion.coastTicks(brakingLookaheadTicks);
        boolean finalApproach = rejoin == null && wpIndex == path.size() - 1
                && Math.hypot(dx, dz) <= Math.max(6.0, brakingRange);
        Vec3 horizontalTravel = new Vec3(dx, 0, dz);

        FlightMotion.Input horizontal;
        Mode mode;
        boolean sprint = false;
        if (!canCoast(view, velocity)) {
            horizontal = FlightMotion.brakingInput(velocity, view.yaw());
            mode = Mode.BRAKE;
        } else if (finalApproach) {
            horizontal = arrivalInput(view, goal, goalStopThreshold);
            mode = Mode.ARRIVE;
        } else {
            // a waypoint beyond braking range is an open run, so lift the cap and sprint into it
            boolean open = rejoin == null && !preserveWaypoint && horizontalTravel.length() > brakingRange;
            sprint = open && distToGoal > 5.0;
            horizontal = passingInput(view, target, exitSpeed(pos, target, next),
                    sprint ? SPRINT_SPEED : MAX_SPEED);
            mode = rejoin != null ? Mode.REJOIN : open ? Mode.CRUISE : Mode.CORNER;
        }

        int vertical = verticalInput(view, pos, dyWp, rejoin != null ? LEVEL_OFF_TOLERANCE : CRUISE_HEIGHT_TOLERANCE);

        long stuckMs = progressTracker.stalledFor(wpIndex, pos.distanceTo(waypointTarget), view.nowMillis());
        if (stuckMs > STUCK_ABORT_MS) {
            state = State.IDLE;
            return Command.repath("Fly route stalled. Requesting a new path.");
        }
        if (stuckMs > STUCK_CLIMB_MS && dyWp > pos.y && isClear(view, pos, pos.add(0, 1, 0))) {
            vertical = 1;
        }
        Steering steering = constrain(view, horizontal, sprint, vertical);

        return new Command(Status.STEER, mode, steering.horizontal(), steering.vertical(), sprint, aim, null);
    }

    private Command beginDecelerate(FlightView view) {
        state = State.DECELERATING;
        decelStartTime = view.nowMillis();
        return tickDecelerate(view);
    }

    private Command tickDecelerate(FlightView view) {
        Vec3 pos = view.position();
        Vec3 velocity = view.velocity();
        if (!isClear(view, pos, goal)) {
            state = State.IDLE;
            return Command.repath("Fly arrival obstructed. Requesting a new path.");
        }
        boolean arrived = pos.distanceToSqr(goal) <= finalWaypointReach * finalWaypointReach * 1.5;
        boolean stopped = velocity.horizontalDistance() < 0.08 && Math.abs(velocity.y) < 0.05;
        if (arrived && stopped) {
            state = State.FINISHED;
            return Command.arrived();
        }
        if (progressTracker.stalledFor(path.size() - 1, pos.distanceTo(goal), view.nowMillis()) > STUCK_ABORT_MS) {
            state = State.IDLE;
            return Command.repath("Fly arrival stalled. Requesting a new path.");
        }

        boolean coastable = canCoast(view, velocity);
        FlightMotion.Input horizontal = coastable
                ? arrivalInput(view, goal, goalStopThreshold)
                : FlightMotion.brakingInput(velocity, view.yaw());
        int vertical = verticalInput(view, pos, goal.y, 0.75);
        Steering steering = constrain(view, horizontal, false, vertical);

        if (!arrived && view.nowMillis() - decelStartTime > DECELERATE_TIMEOUT_MS) {
            // a coast prediction is not arrival; retry the endpoint if momentum left us short or wide
            wpIndex = Math.max(0, path.size() - 1);
            state = State.FLYING;
        }
        return new Command(Status.STEER, coastable ? Mode.ARRIVE : Mode.BRAKE,
                steering.horizontal(), steering.vertical(), false, null, null);
    }

    private Rotation aim(FlightView view, double dx, double dz, double distToGoal) {
        if (lookTarget != null) {
            return AngleUtils.getRotation(view.eyePosition(), lookTarget);
        }
        if (distToGoal > finalWaypointReach) {
            if (Math.hypot(dx, dz) < 0.25) {
                return null;
            }
            return new Rotation((float) Math.toDegrees(Math.atan2(-dx, dz)),
                    usePitchControl ? targetPitch : view.pitch());
        }
        return usePitchControl ? new Rotation(view.yaw(), targetPitch) : null;
    }

    // an intermediate waypoint is a corner to carry speed through, not a place to stop; the heading
    // stays on the waypoint, the only direction known to be clear, and the turn ahead sets the speed
    private FlightMotion.Input passingInput(FlightView view, Vec3 target, double exitSpeed, double maxSpeed) {
        Vec3 offset = target.subtract(view.position());
        double horizontal = offset.horizontalDistance();
        if (horizontal < 1.0e-6) {
            return FlightMotion.horizontalInput(Vec3.ZERO, view.velocity(), view.yaw());
        }
        double speed = Math.min(maxSpeed,
                exitSpeed + horizontal / FlightMotion.coastTicks(brakingLookaheadTicks));
        Vec3 desired = new Vec3(offset.x, 0.0, offset.z).scale(Math.max(speed, 0.08) / horizontal);
        return FlightMotion.horizontalInput(desired, view.velocity(), view.yaw());
    }

    private static double exitSpeed(Vec3 pos, Vec3 target, Vec3 next) {
        if (next == null) {
            return 0.0;
        }
        Vec3 exit = new Vec3(next.x - target.x, 0.0, next.z - target.z);
        double length = exit.horizontalDistance();
        Vec3 entry = new Vec3(target.x - pos.x, 0.0, target.z - pos.z);
        double entryLength = entry.horizontalDistance();
        if (length < 1.0e-6 || entryLength < 1.0e-6) {
            return 0.0;
        }
        double alignment = (entry.x * exit.x + entry.z * exit.z) / (entryLength * length);
        return MAX_SPEED * Math.max(0.0, 0.5 + 0.5 * alignment);
    }

    // a blocked straight line to the waypoint usually means we drifted off a segment that is still
    // flyable, sideways or in height, so try to get back onto it before binning the whole route
    private Vec3 rejoinPoint(FlightView view, Vec3 pos) {
        Vec3 to = waypoint(path.get(wpIndex));
        Vec3 from = wpIndex > 0 ? waypoint(path.get(wpIndex - 1)) : to;
        Vec3 segment = to.subtract(from);
        double lengthSq = segment.lengthSqr();
        double foot = lengthSq < 1.0e-9 ? 1.0
                : Math.max(0.0, Math.min(1.0, pos.subtract(from).dot(segment) / lengthSq));

        for (int step = REJOIN_SAMPLES; step >= 1 && lengthSq >= 1.0e-9; step--) {
            Vec3 candidate = from.add(segment.scale(foot + (1.0 - foot) * step / REJOIN_SAMPLES));
            if (isClear(view, pos, candidate)) {
                return candidate;
            }
        }

        Vec3 levelled = new Vec3(pos.x, from.y + (to.y - from.y) * foot, pos.z);
        if (isClear(view, pos, levelled) && isClear(view, levelled, to)) return levelled;
        return FlightPathClearance.clearCorner(pos, to, (a, b) -> isClear(view, a, b));
    }

    private FlightMotion.Input arrivalInput(FlightView view, Vec3 target, double tolerance) {
        Vec3 offset = target.subtract(view.position());
        Vec3 desired = offset.horizontalDistance() <= tolerance ? Vec3.ZERO
                : FlightMotion.approachVelocity(offset, Vec3.ZERO, 0.0, MAX_SPEED, brakingLookaheadTicks);
        if (desired.horizontalDistance() > 0.0 && desired.horizontalDistance() < 0.08) {
            desired = desired.normalize().scale(0.08);
        }
        return FlightMotion.horizontalInput(desired, view.velocity(), view.yaw());
    }

    private Steering constrain(FlightView view, FlightMotion.Input requested, boolean sprint, int vertical) {
        double acceleration = view.flyingSpeed() * (view.sprinting() || sprint ? 2.0 : 1.0);
        Vec3 velocityAfterLift = view.velocity().add(0, vertical * view.flyingSpeed() * 3.0, 0);
        FlightMotion.Input horizontal = FlightMotion.avoidObstacles(requested, velocityAfterLift, view.yaw(), acceleration,
                velocity -> canCoast(view, velocity));
        if (vertical != 0 && !horizontal.equals(requested)
                && canCoast(view, view.velocity().add(FlightMotion.acceleration(requested, view.yaw(), acceleration)))) {
            return new Steering(requested, 0);
        }
        return new Steering(horizontal, vertical);
    }

    private int verticalInput(FlightView view, Vec3 pos, double waypointY, double tolerance) {
        double dy = waypointY - pos.y;
        int vertical = FlightMotion.verticalInput(dy, view.velocity().y,
                Math.min(tolerance, finalWaypointReach));
        double lookahead = Math.min(Math.abs(dy), 0.5 + Math.abs(view.velocity().y) / 0.4);
        if (vertical != 0 && !isClear(view, pos, pos.add(0, vertical * lookahead, 0))) {
            return 0;
        }
        return vertical;
    }

    private boolean shouldStopNow(FlightView view, Vec3 pos) {
        Vec3 offset = goal.subtract(pos);
        return Math.abs(offset.y) <= finalWaypointReach
                && FlightMotion.shouldCoast(offset, view.velocity(), goalStopThreshold, brakingLookaheadTicks);
    }

    private void skipStaleStartingWaypoints(FlightView view, Vec3 pos) {
        if (wpIndex != 0 || path == null || path.size() < 2) {
            return;
        }
        int bestIndex = 0;
        double bestDistance = pos.distanceToSqr(waypoint(path.getFirst()));
        int scanLimit = Math.min(path.size() - 1, 8);
        for (int i = 1; i <= scanLimit; i++) {
            Node candidate = path.get(i);
            double distance = pos.distanceToSqr(waypoint(candidate));
            if (distance >= bestDistance || !hasClearFlightLine(view, pos, candidate)) {
                continue;
            }
            bestDistance = distance;
            bestIndex = i;
        }
        wpIndex = bestIndex;
    }

    private boolean hasClearFlightLine(FlightView view, Vec3 pos, Node node) {
        return isClear(view, pos, waypoint(node));
    }

    private static boolean isClear(FlightView view, Vec3 from, Vec3 to) {
        return FlightPathClearance.isClear(view.bodyAt(from), to.subtract(from), view::collisions);
    }

    private static boolean canCoast(FlightView view, Vec3 velocity) {
        return FlightPathClearance.canCoastHorizontally(view.bodyAt(view.position()), velocity, view::collisions);
    }
}
