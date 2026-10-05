package dev.aether.macro.fishing;

import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

// a block on the bank close enough to drop a float into the hotspot, tried nearest the player first
final class HotspotSpotFinder {

    enum Status { FOUND, WORKING, EXHAUSTED }

    record Step(Status status, BlockPos spot) {
    }

    // how high the feet stand on a cell, or NaN when nobody can stand there
    interface Ground {
        double standY(BlockPos feet);
    }

    // a throw from that eye that lands in the hotspot, or null
    interface Throw {
        CastSim.CastAim from(BlockPos feet, Vec3 eye);
    }

    private record Candidate(BlockPos feet, Vec3 stand) {
    }

    static final double RING_MIN = 2.0;
    static final double RING_MAX = 6.0;
    static final double MAX_RISE = 4.0;
    static final double PLAYER_CLEARANCE = 1.0;
    private static final int CANDIDATES_PER_TICK = 2;
    static final double STANDING_EYE = 1.62;
    static final double CROUCHING_EYE = 1.27;

    private final List<Candidate> candidates;
    private final Throw cast;
    // the eye the macro will throw from on that spot, crouched or standing
    private final double eyeHeight;
    private int next;

    private HotspotSpotFinder(List<Candidate> candidates, Throw cast, double eyeHeight) {
        this.candidates = candidates;
        this.cast = cast;
        this.eyeHeight = eyeHeight;
    }

    static HotspotSpotFinder live(Minecraft mc, HotspotDetector.Hotspot hotspot, Set<BlockPos> bad,
                                  RandomGenerator random, double eyeHeight) {
        Level level = mc.level;
        WalkabilityChecker checker = new WalkabilityChecker(level);
        Ground ground = feet -> {
            int x = feet.getX();
            int y = feet.getY();
            int z = feet.getZ();
            if (!checker.isWalkable(x, y, z) || !level.getFluidState(feet).isEmpty()
                    || !level.getFluidState(feet.above()).isEmpty()) {
                return Double.NaN;
            }
            return y - 1 + checker.getTopY(x, y - 1, z);
        };
        List<Vec3> players = new ArrayList<>();
        for (Player player : mc.level.players()) {
            if (player != mc.player) {
                players.add(player.position());
            }
        }
        CastAimSearch.Spec spec = CastAimSearch.Spec.GENERAL.withTarget(hotspot.aimPoint());
        Throw cast = (feet, eye) -> {
            CastAimSearch.Step step = new CastAimSearch(level, feet, eye, mc.player.getYRot(), spec, Set.of(),
                    hotspot.liquid(), random).step(1);
            return step.status() == CastAimSearch.Status.FOUND ? step.aim() : null;
        };
        return of(hotspot.centre(), ground, players, bad, mc.player.position(), cast, eyeHeight);
    }

    static HotspotSpotFinder of(Vec3 centre, Ground ground, List<Vec3> players, Set<BlockPos> bad, Vec3 from,
                                Throw cast) {
        return of(centre, ground, players, bad, from, cast, STANDING_EYE);
    }

    static HotspotSpotFinder of(Vec3 centre, Ground ground, List<Vec3> players, Set<BlockPos> bad, Vec3 from,
                                Throw cast, double eyeHeight) {
        return new HotspotSpotFinder(candidates(centre, ground, players, bad, from), cast, eyeHeight);
    }

    Step step() {
        return step(CANDIDATES_PER_TICK);
    }

    Step step(int budget) {
        int tried = 0;
        while (next < candidates.size() && tried < budget) {
            Candidate candidate = candidates.get(next++);
            tried++;
            if (cast.from(candidate.feet(), candidate.stand().add(0.0, eyeHeight, 0.0)) != null) {
                return new Step(Status.FOUND, candidate.feet());
            }
        }
        return new Step(next < candidates.size() ? Status.WORKING : Status.EXHAUSTED, null);
    }

    private static List<Candidate> candidates(Vec3 centre, Ground ground, List<Vec3> players, Set<BlockPos> bad,
                                              Vec3 from) {
        int cx = Mth.floor(centre.x);
        int cz = Mth.floor(centre.z);
        int reach = (int) Math.ceil(RING_MAX);
        int lowest = Mth.floor(centre.y);
        int highest = Mth.floor(centre.y + MAX_RISE) + 1;
        List<Candidate> found = new ArrayList<>();
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                if (!inRingBand(x + 0.5 - centre.x, z + 0.5 - centre.z)) {
                    continue;
                }
                for (int y = lowest; y <= highest; y++) {
                    BlockPos feet = new BlockPos(x, y, z);
                    if (bad.contains(feet)) {
                        continue;
                    }
                    double standY = ground.standY(feet);
                    if (Double.isNaN(standY) || !standsOverSurface(standY, centre.y)) {
                        continue;
                    }
                    Vec3 stand = new Vec3(x + 0.5, standY, z + 0.5);
                    if (!crowded(stand, players)) {
                        found.add(new Candidate(feet, stand));
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(candidate -> candidate.stand().distanceToSqr(from)));
        return found;
    }

    // close enough that the throw stays short and steep, far enough that it is not cast at our own feet
    static boolean inRingBand(double dx, double dz) {
        double distance = Math.hypot(dx, dz);
        return distance >= RING_MIN && distance <= RING_MAX;
    }

    static boolean standsOverSurface(double standY, double surfaceY) {
        double rise = standY - surfaceY;
        return rise >= 0.0 && rise <= MAX_RISE;
    }

    // hotspots draw a crowd, and a block someone already stands on is theirs
    static boolean crowded(Vec3 stand, List<Vec3> players) {
        for (Vec3 player : players) {
            if (player.distanceTo(stand) <= PLAYER_CLEARANCE) {
                return true;
            }
        }
        return false;
    }

    static boolean inRing(Vec3 point, Vec3 centre, double radius) {
        return Math.hypot(point.x - centre.x, point.z - centre.z) <= radius;
    }
}
