package dev.aether.modules.pest.helpers;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

final class PestTrackerTrail {
    static final long CAPTURE_TIMEOUT_MS = 2_500L;
    static final long DISPLAY_TTL_MS = 5_000L;
    private static final int MIN_POINTS = 6;
    private static final int MAX_POINTS = 128;
    private final List<Vec3> points = new ArrayList<>();
    private Vec3 origin;
    private long startedAt;
    private long lastParticleAt;
    private Prediction prediction;

    void begin(Vec3 origin, long now) {
        reset();
        this.origin = origin;
        startedAt = now;
        lastParticleAt = now;
    }

    void reset() {
        points.clear();
        origin = null;
        prediction = null;
        startedAt = 0L;
        lastParticleAt = 0L;
    }

    boolean add(Vec3 point, long now) {
        if (origin == null || now < startedAt || now - startedAt > CAPTURE_TIMEOUT_MS
                || points.size() >= MAX_POINTS || !isFinite(point)) return false;
        if (points.isEmpty()) {
            if (point.distanceTo(origin) > 5.0) return false;
        } else {
            Vec3 step = point.subtract(points.getLast());
            double distance = step.length();
            if (distance < 0.02 || distance > 3.0) return false;
            if (points.size() > 1) {
                Vec3 previous = points.getLast().subtract(points.get(points.size() - 2));
                if (step.normalize().dot(previous.normalize()) < -0.25) return false;
            }
        }
        points.add(point);
        lastParticleAt = now;
        prediction = estimate();
        return true;
    }

    boolean belongsTo(long requestedAt) {
        return origin != null && startedAt >= requestedAt;
    }

    boolean isComplete(long now) {
        return origin != null && (now - startedAt >= CAPTURE_TIMEOUT_MS
                || points.size() >= MIN_POINTS && now - startedAt >= 600L && now - lastParticleAt >= 250L);
    }

    Prediction prediction(long now) {
        return origin != null && now >= startedAt && now - startedAt <= DISPLAY_TTL_MS ? prediction : null;
    }

    List<Vec3> observed(long now) {
        return origin != null && now >= startedAt && now - startedAt <= DISPLAY_TTL_MS ? List.copyOf(points) : List.of();
    }

    private Prediction estimate() {
        if (points.size() < MIN_POINTS) return null;
        double length = 0;
        for (int i = 1; i < points.size(); i++) length += points.get(i).distanceTo(points.get(i - 1));
        if (length < 3.0) return null;

        Vec3 anchor = points.getFirst();
        Vec3[] coefficients = fit(anchor);
        if (coefficients == null) return null;
        double error = 0;
        for (int i = 0; i < points.size(); i++) {
            Vec3 fitted = evaluate(coefficients, i / (double) (points.size() - 1)).add(anchor);
            error += fitted.distanceToSqr(points.get(i));
        }
        if (Math.sqrt(error / points.size()) > 0.6) return null;

        Vec3 tangent = coefficients[1].add(coefficients[2].scale(2));
        if (tangent.length() < 1.0) return null;
        double extensionLength = Math.min(32.0, length);
        double extensionTime = Math.min(1.0, extensionLength / tangent.length());
        Vec3 end = points.getLast();
        Vec3 fittedEnd = evaluate(coefficients, 1);
        List<Vec3> extension = new ArrayList<>();
        extension.add(end);
        double extended = 0;
        for (int i = 1; i <= 32; i++) {
            Vec3 next = end.add(evaluate(coefficients, 1 + extensionTime * i / 32.0).subtract(fittedEnd));
            Vec3 step = next.subtract(extension.getLast());
            double distance = step.length();
            if (!isFinite(next) || distance < 1.0E-6 || step.normalize().dot(tangent.normalize()) < 0.5) break;
            if (extended + distance > extensionLength) {
                extension.add(extension.getLast().add(step.scale((extensionLength - extended) / distance)));
                break;
            }
            extension.add(next);
            extended += distance;
        }
        if (extension.size() < 2) return null;
        return new Prediction(List.copyOf(points), List.copyOf(extension), extension.getLast());
    }

    private Vec3[] fit(Vec3 anchor) {
        double[][] matrix = new double[3][6];
        for (int i = 0; i < points.size(); i++) {
            double t = i / (double) (points.size() - 1);
            double[] basis = {1, t, t * t};
            Vec3 point = points.get(i).subtract(anchor);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) matrix[row][col] += basis[row] * basis[col];
                matrix[row][3] += basis[row] * point.x;
                matrix[row][4] += basis[row] * point.y;
                matrix[row][5] += basis[row] * point.z;
            }
        }
        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int row = col + 1; row < 3; row++) {
                if (Math.abs(matrix[row][col]) > Math.abs(matrix[pivot][col])) pivot = row;
            }
            double[] swap = matrix[col];
            matrix[col] = matrix[pivot];
            matrix[pivot] = swap;
            double divisor = matrix[col][col];
            if (Math.abs(divisor) < 1.0E-9) return null;
            for (int j = col; j < 6; j++) matrix[col][j] /= divisor;
            for (int row = 0; row < 3; row++) {
                if (row == col) continue;
                double factor = matrix[row][col];
                for (int j = col; j < 6; j++) matrix[row][j] -= factor * matrix[col][j];
            }
        }
        return new Vec3[] {
                new Vec3(matrix[0][3], matrix[0][4], matrix[0][5]),
                new Vec3(matrix[1][3], matrix[1][4], matrix[1][5]),
                new Vec3(matrix[2][3], matrix[2][4], matrix[2][5])
        };
    }

    private static Vec3 evaluate(Vec3[] coefficients, double t) {
        return coefficients[0].add(coefficients[1].scale(t)).add(coefficients[2].scale(t * t));
    }

    private static boolean isFinite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    record Prediction(List<Vec3> observed, List<Vec3> extension, Vec3 target) { }
}
