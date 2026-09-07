package dev.aether.modules.pathfinding;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.execution.PathExecutor;
import dev.aether.modules.pathfinding.movement.PathSmoother;
import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.pathfinder.AStarPathfinder;
import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration;
import dev.aether.modules.pathfinding.rotation.RotationExecutor;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;

public final class PathfindingClientTest implements FabricClientGameTest {
    private static final String LOG_PREFIX = "AETHER_PATHFINDING_TEST: ";
    private static final int MAX_ROUTE_TICKS = 400;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(true)
                .adjustSettings(settings -> {
                    settings.setName("Aether pathfinding regressions");
                    settings.setAllowCommands(true);
                }).create()) {
            world.getClientLevel().waitForChunksDownload();
            world.getServer().runCommand("gamemode survival @p");
            world.getServer().runCommand("effect give @p minecraft:resistance infinite 255 true");
            context.runOnClient(client -> {
                AetherConfig.PATHFINDER_SPRINT.set(true);
                AetherConfig.PATHFINDER_RAYCAST_JUMP.set(true);
                AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.set(1);
                AetherConfig.PATHFINDER_JUMP_LOOKAHEAD_TICKS.set(2.0f);
                AetherConfig.PATHFINDER_AIM_LOOKAHEAD.set(3.5f);
                AetherConfig.PATHFINDER_TURN_SPEED.set(240.0f);
                AetherConfig.PATHFINDER_STUCK_TIMEOUT_MS.set(1800);
                client.options.autoJump().set(false);
                System.out.println(LOG_PREFIX + "gameDir=" + client.gameDirectory);
            });

            for (Course course : Course.values()) {
                buildCourse(world, course);
                for (double speed : course == Course.SLABS || course == Course.STAIRS
                        ? new double[]{0.1, 0.4} : new double[]{0.1}) {
                    preparePlayer(context, world, course, speed);
                    List<Node> route = plan(context, course);
                    verifyCourseRoute(course, route);
                    runRoute(context, course, speed, route);
                }
            }
            System.out.println(LOG_PREFIX + "PASS all seven generated movement courses");
        }
    }

    private static void buildCourse(TestSingleplayerContext world, Course course) {
        world.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (int x = -3; x <= 3; x++) {
                for (int z = -4; z <= 16; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
                    for (int y = 100; y <= 143; y++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                    switch (course) {
                        case CARPET_LEDGE -> {
                            if (z <= 0) {
                                level.setBlockAndUpdate(new BlockPos(x, 100, z), Blocks.LIGHT_BLUE_CARPET.defaultBlockState());
                            } else if (z == 1) {
                                level.setBlockAndUpdate(new BlockPos(x, 100, z), Blocks.OAK_PLANKS.defaultBlockState());
                                if (Math.abs(x) == 2) {
                                    level.setBlockAndUpdate(new BlockPos(x, 101, z), Blocks.FLOWER_POT.defaultBlockState());
                                }
                            }
                            if (z <= 1) {
                                level.setBlockAndUpdate(new BlockPos(x, 103, z), Blocks.OAK_PLANKS.defaultBlockState());
                                if (Math.abs(x) == 3) {
                                    for (int y = 100; y <= 102; y++) {
                                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState());
                                    }
                                }
                            }
                        }
                        case SLABS -> {
                            if (z >= 1 && z <= 10) {
                                level.setBlockAndUpdate(new BlockPos(x, 100 + (z - 1) / 2, z),
                                        (z % 2 == 1 ? Blocks.STONE_SLAB : Blocks.STONE).defaultBlockState());
                            } else if (z > 10) {
                                level.setBlockAndUpdate(new BlockPos(x, 104, z), Blocks.STONE.defaultBlockState());
                            }
                        }
                        case STAIRS -> {
                            if (z >= 1 && z <= 6) {
                                level.setBlockAndUpdate(new BlockPos(x, 99 + z, z),
                                        Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                                                .setValue(StairBlock.FACING, Direction.SOUTH));
                            } else if (z > 6) {
                                level.setBlockAndUpdate(new BlockPos(x, 105, z), Blocks.STONE.defaultBlockState());
                            }
                        }
                        case JUMP -> {
                            if (z == 1) {
                                level.setBlockAndUpdate(new BlockPos(x, 100, z), Blocks.STONE.defaultBlockState());
                            } else if (z == 2) {
                                level.setBlockAndUpdate(new BlockPos(x, 101, z), Blocks.STONE_SLAB.defaultBlockState());
                            } else if (z > 2) {
                                level.setBlockAndUpdate(new BlockPos(x, 101, z), Blocks.STONE.defaultBlockState());
                            }
                        }
                        case DROP -> {
                            if (z <= 0) {
                                level.setBlockAndUpdate(new BlockPos(x, 139, z), Blocks.STONE.defaultBlockState());
                            }
                        }
                    }
                }
            }
        });
    }

    private static void preparePlayer(ClientGameTestContext context, TestSingleplayerContext world,
                                      Course course, double speed) {
        world.getServer().runCommand("attribute @p minecraft:movement_speed base set " + speed);
        world.getServer().runCommand(switch (course) {
            case DROP -> "tp @p 0.95 140 -1.5 0 0";
            case CARPET_LEDGE -> "tp @p 0.8 100.0625 0.5 0 0";
            default -> "tp @p 0.8 100 0.5 0 0";
        });
        context.waitFor(client -> client.player != null
                && Math.abs(client.player.getY() - course.start().y) < 0.1);
        context.waitTicks(20);
        context.runOnClient(client -> client.player.setDeltaMovement(Vec3.ZERO));
    }

    @SuppressWarnings("unchecked")
    private static List<Node> plan(ClientGameTestContext context, Course course) {
        return context.computeOnClient(client -> {
            try {
                WalkabilityChecker checker = new WalkabilityChecker(client.level);
                var configMethod = PathfindingManager.class.getDeclaredMethod(
                        "createWalkPathfinderConfiguration", WalkabilityChecker.class, boolean.class);
                configMethod.setAccessible(true);
                var config = (PathfinderConfiguration) configMethod.invoke(null, checker, false);
                var result = new AStarPathfinder(config).findPath(course.start(), course.goal())
                        .toCompletableFuture().join();
                if (!result.successful()) throw new AssertionError(course + ": planner did not find a full route");

                var nodesMethod = PathfindingManager.class.getDeclaredMethod(
                        "toNodeList", Collection.class, PathfinderConfiguration.class);
                nodesMethod.setAccessible(true);
                List<Node> nodes = (List<Node>) nodesMethod.invoke(null, result.getPath().collect(), config);
                List<Node> smoothed = PathSmoother.smooth(nodes, checker);
                var collapseMethod = PathfindingManager.class.getDeclaredMethod("collapseAscendingStacks", List.class);
                collapseMethod.setAccessible(true);
                List<Node> keynodes = (List<Node>) collapseMethod.invoke(null, smoothed);
                for (Node node : keynodes) node.isKeynode = true;
                var intermediatesMethod = PathfindingManager.class.getDeclaredMethod(
                        "insertIntermediates", List.class, WalkabilityChecker.class);
                intermediatesMethod.setAccessible(true);
                return (List<Node>) intermediatesMethod.invoke(null, keynodes, checker);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not invoke the walking planner pipeline", error);
            }
        });
    }

    private static void verifyCourseRoute(Course course, List<Node> route) {
        if ((course == Course.JUMP || course == Course.CARPET_LEDGE)
                && route.stream().noneMatch(node -> node.position.flooredZ() == 1
                && node.position.flooredY() == 101 && Math.abs(node.position.flooredX()) <= 3)) {
            throw new AssertionError("Jump route bypassed the full-block ledge: " + route);
        }
        if (course == Course.DROP) {
            int verticalDistance = 0;
            for (int i = 1; i < route.size(); i++) {
                var from = route.get(i - 1).position;
                var to = route.get(i).position;
                if (from.flooredX() == to.flooredX() && from.flooredZ() == to.flooredZ()) {
                    verticalDistance += Math.max(0, from.flooredY() - to.flooredY());
                }
            }
            if (verticalDistance < 35) throw new AssertionError("Drop route did not contain the vertical fall");
        }
    }

    private static void runRoute(ClientGameTestContext context, Course course, double speed, List<Node> route) {
        PathExecutor executor = new PathExecutor();
        context.runOnClient(client -> {
            RotationExecutor.stopRotating();
            var end = course.goal();
            executor.start(route, end.flooredX(), end.flooredY(), end.flooredZ(), false);
            executor.setAllowReplan(false);
        });
        boolean requestedJump = false;
        int fallingSamples = 0;
        double previousZ = course.start().centeredZ();
        try {
            for (int tick = 0; tick < MAX_ROUTE_TICKS; tick++) {
                Sample sample = context.computeOnClient(client -> {
                    executor.tick(client);
                    return new Sample(client.player.position(), client.player.getEyeY(), executor.getAimPoint(),
                            executor.getMovementDirection(), executor.getState(), executor.getWaypointIndex(),
                            client.options.keyJump.isDown());
                });
                requestedJump |= sample.jump();
                if (course == Course.SLABS || course == Course.STAIRS) {
                    if (sample.movement().z < -0.1 || sample.feet().z < previousZ - 0.03) {
                        throw new AssertionError(course + ": moved backwards at " + sample);
                    }
                }
                previousZ = sample.feet().z;
                if (course == Course.DROP && sample.feet().y < 135 && sample.feet().y > 102) {
                    fallingSamples++;
                    if (sample.aim() == null || sample.aim().y > sample.eyeY() + 0.1) {
                        throw new AssertionError("Drop aim stayed above the falling player: " + sample);
                    }
                }
                if (tick % 20 == 0 || sample.state() != PathExecutor.State.WALKING) {
                    System.out.println(LOG_PREFIX + course + " speed=" + speed + " tick=" + tick + " " + sample);
                }
                if (sample.state() == PathExecutor.State.FINISHED) {
                    if ((course == Course.JUMP || course == Course.CARPET_LEDGE) && !requestedJump) {
                        throw new AssertionError("Ledge was never jumped");
                    }
                    if (course == Course.DROP && fallingSamples == 0) throw new AssertionError("Fall was never observed");
                    System.out.println(LOG_PREFIX + "PASS " + course + " speed=" + speed + " ticks=" + tick);
                    return;
                }
                if (sample.state() != PathExecutor.State.WALKING) {
                    throw new AssertionError(course + ": executor stopped before reaching the goal: " + sample);
                }
                context.waitTick();
            }
            throw new AssertionError(course + ": route did not finish within " + MAX_ROUTE_TICKS + " ticks");
        } finally {
            context.runOnClient(executor::stop);
        }
    }

    private enum Course {
        CARPET_LEDGE(100, 8), SLABS(105, 14), STAIRS(106, 14), JUMP(102, 10), DROP(100, 8);

        private final int goalY;
        private final int goalZ;

        Course(int goalY, int goalZ) {
            this.goalY = goalY;
            this.goalZ = goalZ;
        }

        PathPosition start() {
            return this == DROP ? new PathPosition(0, 140, -2) : new PathPosition(0, 100, 0);
        }

        PathPosition goal() {
            return new PathPosition(0, goalY, goalZ);
        }
    }

    private record Sample(Vec3 feet, double eyeY, Vec3 aim, Vec3 movement,
                          PathExecutor.State state, int segment, boolean jump) {}
}
