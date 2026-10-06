package dev.aether.ui.orbit;

// semi-implicit damped spring; every orbit motion uses one so it overshoots a touch and settles
final class OrbitSpring {
    float x;
    float v;
    float t;
    private final float k;
    private final float c;

    OrbitSpring(float x, float k, float c) {
        this.x = x;
        this.t = x;
        this.k = k;
        this.c = c;
    }

    void step(float dt) {
        float a = -k * (x - t) - c * v;
        v += a * dt;
        x += v * dt;
    }

    void snap(float value) {
        x = t = value;
        v = 0f;
    }

    boolean settled() {
        return Math.abs(x - t) < 1e-4f && Math.abs(v) < 1e-3f;
    }
}
