package dev.aether.modules.pathfinding.execution

import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.rotation.AngleUtils
import dev.aether.modules.pathfinding.rotation.Rotation
import net.minecraft.world.phys.Vec3
import java.util.ArrayList

class FlightGuidance {
    enum class State { IDLE, FLYING, DECELERATING, FINISHED }
    enum class Status { HOLD, STEER, REPATH, ARRIVED }
    enum class Mode { NONE, BRAKE, ARRIVE, CORNER, CRUISE, REJOIN }
    private class Steering(val horizontal: FlightMotion.Input, val vertical: Int)
    class Command(private val status: Status, private val mode: Mode, private val horizontal: FlightMotion.Input, private val vertical: Int, private val sprint: Boolean, private val aim: Rotation?, private val note: String?) {
        fun status() = status; fun mode() = mode; fun horizontal() = horizontal; fun vertical() = vertical; fun sprint() = sprint; fun aim() = aim; fun note() = note
        companion object {
            fun hold() = Command(Status.HOLD, Mode.NONE, FlightMotion.Input(0, 0), 0, false, null, null)
            fun repath(note: String) = Command(Status.REPATH, Mode.NONE, FlightMotion.Input(0, 0), 0, false, null, note)
            fun arrived() = Command(Status.ARRIVED, Mode.NONE, FlightMotion.Input(0, 0), 0, false, null, null)
        }
    }
    private val progressTracker = FlightProgressTracker()
    private var state = State.IDLE
    private var path: List<Node>? = null
    private var wpIndex = 0
    private var goal = Vec3.ZERO
    private var decelStartTime = 0L
    private var usePitchControl = false
    private var targetPitch = 0f
    private var lookTarget: Vec3? = null
    private var finalWaypointReach = REACH
    private var goalStopThreshold = STOP_THRESH
    private var brakingLookaheadTicks = 2.0
    fun start(path: List<Node>, goalX: Int, goalY: Int, goalZ: Int) { this.path = ArrayList(path); goal = waypoint(goalX, goalY, goalZ); wpIndex = 0; decelStartTime = 0; progressTracker.reset(); usePitchControl = false; lookTarget = null; finalWaypointReach = REACH; goalStopThreshold = STOP_THRESH; state = State.FLYING }
    fun stop() { state = State.IDLE }
    fun state() = state
    fun waypointIndex() = wpIndex
    fun path(): List<Node>? = path
    fun setPitchControl(pitch: Float) { usePitchControl = true; targetPitch = pitch; lookTarget = null }
    fun setLookTargetRotation(lookTarget: Vec3?) { this.lookTarget = lookTarget; usePitchControl = false }
    fun setPreciseGoalTolerance(tolerance: Double) { val clamped = Math.max(0.01, tolerance); finalWaypointReach = clamped; goalStopThreshold = clamped }
    fun setBrakingLookaheadTicks(ticks: Double) { brakingLookaheadTicks = ticks }

    fun tick(view: FlightView): Command {
        if (state == State.DECELERATING) return tickDecelerate(view)
        if (state != State.FLYING) return Command.hold()
        val route = path
        if (route == null || route.isEmpty()) { state = State.FINISHED; return Command.arrived() }
        val pos = view.position()
        skipStaleStartingWaypoints(view, pos)
        var rejoin: Vec3? = null; var preserveWaypoint = false
        while (wpIndex < route.size) {
            val wp = route[wpIndex]
            if (!hasClearFlightLine(view, pos, wp)) {
                rejoin = rejoinPoint(view, pos)
                if (rejoin == null) { state = State.IDLE; return Command.repath("Fly route obstructed. Requesting a new path.") }
                break
            }
            val target = waypoint(wp); val distSq = pos.distanceToSqr(target)
            if (wpIndex >= route.size - 1 && distSq <= finalWaypointReach * finalWaypointReach * 1.5) { wpIndex++; break }
            val waypointReach = if (wpIndex == route.size - 1) finalWaypointReach else REACH
            var reached = distSq <= waypointReach * waypointReach
            if (!reached && wpIndex > 0 && wpIndex < route.size - 1) {
                val toWp = target.subtract(pos); val pathDir = target.subtract(waypoint(route[wpIndex - 1])).normalize()
                if (toWp.dot(pathDir) < 0) reached = true
            }
            if (reached && wpIndex + 1 < route.size && !hasClearFlightLine(view, pos, route[wpIndex + 1])) {
                if (distSq <= CORNER_REACH * CORNER_REACH) { state = State.IDLE; return Command.repath("Next fly waypoint obstructed. Requesting a new path.") }
                preserveWaypoint = true; break
            }
            if (reached) wpIndex++ else break
        }
        val distToGoal = pos.distanceTo(goal)
        if (wpIndex >= route.size - 1 && shouldStopNow(view, pos)) return beginDecelerate(view)
        if (wpIndex >= route.size) return beginDecelerate(view)
        val waypointTarget = waypoint(route[wpIndex]); val target = rejoin ?: waypointTarget
        val next = if (rejoin != null) waypointTarget else if (wpIndex + 1 < route.size) waypoint(route[wpIndex + 1]) else null
        val dx = target.x - pos.x; val dz = target.z - pos.z; val dyWp = target.y
        val aim = aim(view, dx, dz, distToGoal); val velocity = view.velocity()
        val brakingRange = 3.0 + velocity.horizontalDistance() * FlightMotion.coastTicks(brakingLookaheadTicks)
        val finalApproach = rejoin == null && wpIndex == route.size - 1 && Math.hypot(dx, dz) <= Math.max(6.0, brakingRange)
        val horizontalTravel = Vec3(dx, 0.0, dz)
        var horizontal: FlightMotion.Input; var mode: Mode; var sprint = false
        if (!canCoast(view, velocity)) { horizontal = FlightMotion.brakingInput(velocity, view.yaw()); mode = Mode.BRAKE }
        else if (finalApproach) { horizontal = arrivalInput(view, goal, goalStopThreshold); mode = Mode.ARRIVE }
        else {
            val open = rejoin == null && !preserveWaypoint && horizontalTravel.length() > brakingRange
            sprint = open && distToGoal > 5.0
            horizontal = passingInput(view, target, exitSpeed(pos, target, next), if (sprint) SPRINT_SPEED else MAX_SPEED)
            mode = if (rejoin != null) Mode.REJOIN else if (open) Mode.CRUISE else Mode.CORNER
        }
        var vertical = verticalInput(view, pos, dyWp, if (rejoin != null) LEVEL_OFF_TOLERANCE else CRUISE_HEIGHT_TOLERANCE)
        val stuckMs = progressTracker.stalledFor(wpIndex, pos.distanceTo(waypointTarget), view.nowMillis())
        if (stuckMs > STUCK_ABORT_MS) { state = State.IDLE; return Command.repath("Fly route stalled. Requesting a new path.") }
        if (stuckMs > STUCK_CLIMB_MS && dyWp > pos.y && isClear(view, pos, pos.add(0.0, 1.0, 0.0))) vertical = 1
        val steering = constrain(view, horizontal, sprint, vertical)
        return Command(Status.STEER, mode, steering.horizontal, steering.vertical, sprint, aim, null)
    }
    private fun beginDecelerate(view: FlightView): Command { state = State.DECELERATING; decelStartTime = view.nowMillis(); return tickDecelerate(view) }
    private fun tickDecelerate(view: FlightView): Command {
        val pos = view.position(); val velocity = view.velocity()
        if (!isClear(view, pos, goal)) { state = State.IDLE; return Command.repath("Fly arrival obstructed. Requesting a new path.") }
        val arrived = pos.distanceToSqr(goal) <= finalWaypointReach * finalWaypointReach * 1.5
        val stopped = velocity.horizontalDistance() < 0.08 && Math.abs(velocity.y) < 0.05
        if (arrived && stopped) { state = State.FINISHED; return Command.arrived() }
        val currentPath = path!!
        if (progressTracker.stalledFor(currentPath.size - 1, pos.distanceTo(goal), view.nowMillis()) > STUCK_ABORT_MS) { state = State.IDLE; return Command.repath("Fly arrival stalled. Requesting a new path.") }
        val coastable = canCoast(view, velocity)
        val horizontal = if (coastable) arrivalInput(view, goal, goalStopThreshold) else FlightMotion.brakingInput(velocity, view.yaw())
        val vertical = verticalInput(view, pos, goal.y, 0.75); val steering = constrain(view, horizontal, false, vertical)
        if (!arrived && view.nowMillis() - decelStartTime > DECELERATE_TIMEOUT_MS) { wpIndex = Math.max(0, currentPath.size - 1); state = State.FLYING }
        return Command(Status.STEER, if (coastable) Mode.ARRIVE else Mode.BRAKE, steering.horizontal, steering.vertical, false, null, null)
    }
    private fun aim(view: FlightView, dx: Double, dz: Double, distToGoal: Double): Rotation? {
        val target = lookTarget
        if (target != null) return AngleUtils.getRotation(view.eyePosition(), target)
        if (distToGoal > finalWaypointReach) {
            if (Math.hypot(dx, dz) < 0.25) return null
            return Rotation(Math.toDegrees(Math.atan2(-dx, dz)).toFloat(), if (usePitchControl) targetPitch else view.pitch())
        }
        return if (usePitchControl) Rotation(view.yaw(), targetPitch) else null
    }
    private fun passingInput(view: FlightView, target: Vec3, exitSpeed: Double, maxSpeed: Double): FlightMotion.Input {
        val offset = target.subtract(view.position()); val horizontal = offset.horizontalDistance()
        if (horizontal < 1.0e-6) return FlightMotion.horizontalInput(Vec3.ZERO, view.velocity(), view.yaw())
        val speed = Math.min(maxSpeed, exitSpeed + horizontal / FlightMotion.coastTicks(brakingLookaheadTicks))
        val desired = Vec3(offset.x, 0.0, offset.z).scale(Math.max(speed, 0.08) / horizontal)
        return FlightMotion.horizontalInput(desired, view.velocity(), view.yaw())
    }
    private fun rejoinPoint(view: FlightView, pos: Vec3): Vec3? {
        val route = path!!; val to = waypoint(route[wpIndex]); val from = if (wpIndex > 0) waypoint(route[wpIndex - 1]) else to
        val segment = to.subtract(from); val lengthSq = segment.lengthSqr()
        val foot = if (lengthSq < 1.0e-9) 1.0 else Math.max(0.0, Math.min(1.0, pos.subtract(from).dot(segment) / lengthSq))
        for (step in REJOIN_SAMPLES downTo 1) if (lengthSq >= 1.0e-9) {
            val candidate = from.add(segment.scale(foot + (1.0 - foot) * step / REJOIN_SAMPLES))
            if (isClear(view, pos, candidate)) return candidate
        }
        val levelled = Vec3(pos.x, from.y + (to.y - from.y) * foot, pos.z)
        if (isClear(view, pos, levelled) && isClear(view, levelled, to)) return levelled
        return FlightPathClearance.clearCorner(pos, to, java.util.function.BiPredicate { a, b -> isClear(view, a, b) })
    }
    private fun arrivalInput(view: FlightView, target: Vec3, tolerance: Double): FlightMotion.Input {
        val offset = target.subtract(view.position())
        var desired = if (offset.horizontalDistance() <= tolerance) Vec3.ZERO else FlightMotion.approachVelocity(offset, Vec3.ZERO, 0.0, MAX_SPEED, brakingLookaheadTicks)
        if (desired.horizontalDistance() > 0.0 && desired.horizontalDistance() < 0.08) desired = desired.normalize().scale(0.08)
        return FlightMotion.horizontalInput(desired, view.velocity(), view.yaw())
    }
    private fun constrain(view: FlightView, requested: FlightMotion.Input, sprint: Boolean, vertical: Int): Steering {
        val acceleration = view.flyingSpeed() * if (view.sprinting() || sprint) 2.0 else 1.0
        val velocityAfterLift = view.velocity().add(0.0, vertical * view.flyingSpeed() * 3.0, 0.0)
        val horizontal = FlightMotion.avoidObstacles(requested, velocityAfterLift, view.yaw(), acceleration, java.util.function.Predicate { canCoast(view, it) })
        if (vertical != 0 && horizontal != requested && canCoast(view, view.velocity().add(FlightMotion.acceleration(requested, view.yaw(), acceleration)))) return Steering(requested, 0)
        return Steering(horizontal, vertical)
    }
    private fun verticalInput(view: FlightView, pos: Vec3, waypointY: Double, tolerance: Double): Int {
        val dy = waypointY - pos.y; val vertical = FlightMotion.verticalInput(dy, view.velocity().y, Math.min(tolerance, finalWaypointReach))
        val lookahead = Math.min(Math.abs(dy), 0.5 + Math.abs(view.velocity().y) / 0.4)
        return if (vertical != 0 && !isClear(view, pos, pos.add(0.0, vertical * lookahead, 0.0))) 0 else vertical
    }
    private fun shouldStopNow(view: FlightView, pos: Vec3): Boolean { val offset = goal.subtract(pos); return Math.abs(offset.y) <= finalWaypointReach && FlightMotion.shouldCoast(offset, view.velocity(), goalStopThreshold, brakingLookaheadTicks) }
    private fun skipStaleStartingWaypoints(view: FlightView, pos: Vec3) {
        val route = path ?: return
        if (wpIndex != 0 || route.size < 2) return
        var bestIndex = 0; var bestDistance = pos.distanceToSqr(waypoint(route.first())); val scanLimit = Math.min(route.size - 1, 8)
        for (i in 1..scanLimit) { val candidate = route[i]; val distance = pos.distanceToSqr(waypoint(candidate)); if (distance >= bestDistance || !hasClearFlightLine(view, pos, candidate)) continue; bestDistance = distance; bestIndex = i }
        wpIndex = bestIndex
    }
    private fun hasClearFlightLine(view: FlightView, pos: Vec3, node: Node) = isClear(view, pos, waypoint(node))
    companion object {
        const val REACH = 1.5; const val CORNER_REACH = 0.1; const val STOP_THRESH = 0.5; const val MAX_SPEED = 0.6; const val SPRINT_SPEED = 1.0
        private const val STUCK_CLIMB_MS = 1500L; private const val STUCK_ABORT_MS = 3000L; private const val DECELERATE_TIMEOUT_MS = 650L; private const val REJOIN_SAMPLES = 4
        internal const val CRUISE_HEIGHT_TOLERANCE = 0.25; private const val LEVEL_OFF_TOLERANCE = 0.1
        @JvmStatic fun waypoint(x: Int, y: Int, z: Int) = Vec3(x + 0.5, y + 0.15, z + 0.5)
        @JvmStatic fun waypoint(node: Node) = waypoint(node.position.flooredX(), node.position.flooredY(), node.position.flooredZ())
        private fun exitSpeed(pos: Vec3, target: Vec3, next: Vec3?): Double {
            if (next == null) return 0.0
            val exit = Vec3(next.x - target.x, 0.0, next.z - target.z); val length = exit.horizontalDistance()
            val entry = Vec3(target.x - pos.x, 0.0, target.z - pos.z); val entryLength = entry.horizontalDistance()
            if (length < 1.0e-6 || entryLength < 1.0e-6) return 0.0
            val alignment = (entry.x * exit.x + entry.z * exit.z) / (entryLength * length)
            return MAX_SPEED * Math.max(0.0, 0.5 + 0.5 * alignment)
        }
        private fun isClear(view: FlightView, from: Vec3, to: Vec3) = FlightPathClearance.isClear(view.bodyAt(from), to.subtract(from), java.util.function.Function { view.collisions(it) })
        private fun canCoast(view: FlightView, velocity: Vec3) = FlightPathClearance.canCoastHorizontally(view.bodyAt(view.position()), velocity, java.util.function.Function { view.collisions(it) })
    }
}
