package dev.aether.modules.pathfinding.execution;

import java.util.ArrayList;
import java.util.List;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.rotation.EasingType;
import dev.aether.modules.pathfinding.rotation.Rotation;
import dev.aether.modules.pathfinding.rotation.RotationExecutor;
import dev.aether.modules.pathfinding.rotation.strategy.TimedEaseStrategy;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FlyExecutor {

    public enum State {
        IDLE, FLYING, DECELERATING, FINISHED
    }

    private final FlightGuidance guidance = new FlightGuidance();

    private State state = State.IDLE;
    private Runnable onFinished;

    // --- Public API ----------------------------------------------------------

    public void start(List<Node> path, int goalX, int goalY, int goalZ) {
        start(path, goalX, goalY, goalZ, null);
    }

    public void start(List<Node> path, int goalX, int goalY, int goalZ, Runnable onFinished) {
        guidance.start(new ArrayList<>(path), goalX, goalY, goalZ);
        guidance.setBrakingLookaheadTicks(AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS.get());
        this.onFinished = onFinished;
        state = State.FLYING;
    }

    public void setPitchControl(float pitch) {
        guidance.setPitchControl(pitch);
    }

    public void setLookTargetRotation(Vec3 lookTarget) {
        guidance.setLookTargetRotation(lookTarget);
    }

    public void setPreciseGoalTolerance(double tolerance) {
        guidance.setPreciseGoalTolerance(tolerance);
    }

    public State getState() {
        return state;
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (ClientUtils.isInventoryScreenOpen()) {
            releaseAll(mc);
            return;
        }
        if (state != State.FLYING && state != State.DECELERATING) {
            return;
        }

        guidance.setBrakingLookaheadTicks(AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS.get());
        FlightGuidance.Command command = guidance.tick(new MinecraftFlightView(mc));

        if (command.note() != null) {
            ClientUtils.sendDebugMessage(command.note());
        }

        switch (command.status()) {
            case REPATH -> {
                stop(mc);
                return;
            }
            case ARRIVED -> {
                finish(mc);
                return;
            }
            case HOLD -> {
                return;
            }
            case STEER -> {
            }
        }

        if (command.aim() != null) {
            rotateSmoothly(mc, command.aim());
        }
        FlightMotion.apply(mc, command.horizontal());
        ClientUtils.setKeyMappingState(mc.options.keySprint, command.sprint());
        ClientUtils.setKeyMappingState(mc.options.keyJump, command.vertical() > 0);
        ClientUtils.setKeyMappingState(mc.options.keyShift,
                command.vertical() < 0 && mc.player.getAbilities().flying);

        state = switch (guidance.state()) {
            case DECELERATING -> State.DECELERATING;
            case FINISHED -> State.FINISHED;
            case IDLE -> State.IDLE;
            case FLYING -> State.FLYING;
        };

        ClientUtils.sendDebugMessage(String.format("fly wp=%d/%d state=%s mode=%s",
                Math.min(guidance.waypointIndex() + 1, guidance.path().size()),
                guidance.path().size(), state, command.mode()));
    }

    public void stop(Minecraft mc) {
        state = State.IDLE;
        guidance.stop();
        RotationExecutor.stopRotating();
        releaseAll(mc);
    }

    public void releaseAll(Minecraft mc) {
        if (mc == null || mc.options == null)
            return;
        ClientUtils.setKeyMappingState(mc.options.keyUp, false);
        ClientUtils.setKeyMappingState(mc.options.keyDown, false);
        ClientUtils.setKeyMappingState(mc.options.keyLeft, false);
        ClientUtils.setKeyMappingState(mc.options.keyRight, false);
        ClientUtils.setKeyMappingState(mc.options.keyJump, false);
        ClientUtils.setKeyMappingState(mc.options.keySprint, false);
        ClientUtils.setKeyMappingState(mc.options.keyShift, false);
    }

    // --- Internal helpers ----------------------------------------------------

    private void finish(Minecraft mc) {
        RotationExecutor.stopRotating();
        releaseAll(mc);
        state = State.FINISHED;
        if (onFinished != null) {
            onFinished.run();
        }
    }

    private void rotateSmoothly(Minecraft mc, Rotation desired) {
        FlightAim.Plan plan = FlightAim.plan(mc.player.getYRot(), mc.player.getXRot(), desired,
                RotationExecutor.isRotating(), RotationExecutor.getTargetYaw(), RotationExecutor.getTargetPitch());
        if (plan != null) {
            RotationExecutor.rotateTo(plan.rotation(),
                    new TimedEaseStrategy(EasingType.EASE_IN_OUT_CUBIC, plan.durationMs()));
        }
    }

    private record MinecraftFlightView(Minecraft mc) implements FlightView {
        @Override
        public Vec3 position() {
            return mc.player.position();
        }

        @Override
        public Vec3 eyePosition() {
            return mc.player.getEyePosition();
        }

        @Override
        public Vec3 velocity() {
            return mc.player.getDeltaMovement();
        }

        @Override
        public float yaw() {
            return mc.player.getYRot();
        }

        @Override
        public float pitch() {
            return mc.player.getXRot();
        }

        @Override
        public double flyingSpeed() {
            return mc.player.getAbilities().getFlyingSpeed();
        }

        @Override
        public boolean sprinting() {
            return mc.player.isSprinting() || mc.options.keySprint.isDown();
        }

        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public AABB bodyAt(Vec3 feet) {
            return mc.player.getBoundingBox().move(feet.subtract(mc.player.position()));
        }

        @Override
        public Iterable<AABB> collisions(AABB search) {
            List<AABB> obstacles = new ArrayList<>();
            for (var shape : mc.level.getBlockCollisions(mc.player, search)) {
                obstacles.addAll(shape.toAabbs());
            }
            return obstacles;
        }
    }
}
