package dev.aether.renderer;

import dev.aether.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import org.lwjgl.nanovg.NanoVG;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Singleton manager for the menu background (particles and ripples).
 * Centrally manages state so that animations persist when switching between screens.
 */
public final class AetherBackground {

    public static final AetherBackground INSTANCE = new AetherBackground();

    // -- Background -------------------------------------------------------------
    private static final int BG = 0xFF0A0A0F;

    // -- Particles --------------------------------------------------------------
    private static final int N = 400;
    private final float[] px    = new float[N];
    private final float[] py    = new float[N];
    private final float[] pspd  = new float[N];
    private final float[] prad  = new float[N];
    private final float[] pop   = new float[N];
    private final boolean[] pstar = new boolean[N];
    private final float[] vx = new float[N];
    private final float[] vy = new float[N];

    private final Random rng = new Random();

    // Frame-rate independent damping
    private static final float VEL_DAMPING = 4.2f;

    // -- Shockwaves ---------------------------------------------------------------
    private static final int   MAX_WAVES        = 8;
    private static final float WAVE_SPEED       = 200f;
    private static final float WAVE_BAND        = 24f;
    private static final float WAVE_IMPULSE     = 500f;
    private static final float WAVE_THICK_START = 10f;
    private static final float WAVE_THICK_END   =  4f;

    private static final class Shock {
        final float x, y;
        final long  t0;
        Shock(float x, float y, long t0) { this.x = x; this.y = y; this.t0 = t0; }
    }
    private final ArrayDeque<Shock> shockwaves = new ArrayDeque<>(MAX_WAVES);

    // -- Ripple effect ---------------------------------------------------------
    private final RippleEffect rippleEffect = new RippleEffect();

    // Temp arrays for the GPU blur pass
    private final float[] rCx  = new float[4];
    private final float[] rCy  = new float[4];
    private final float[] rRad = new float[4];
    private final float[] rThi = new float[4];
    private final float[] rStr = new float[4];

    private long lastTime;
    private boolean initialized = false;
    private int lastWidth = 0, lastHeight = 0;

    private AetherBackground() {}

    public void init(int width, int height) {
        for (int i = 0; i < N; i++) {
            pstar[i] = rng.nextFloat() < 0.15f;
            px[i]    = rng.nextFloat() * width;
            py[i]    = rng.nextFloat() * height;
            pspd[i]  = pstar[i] ? 5f + rng.nextFloat() * 8f : 8f + rng.nextFloat() * 22f;
            prad[i]  = pstar[i] ? 1.2f + rng.nextFloat() * 0.8f : 0.4f + rng.nextFloat() * 0.7f;
            pop[i]   = pstar[i] ? 0.30f + rng.nextFloat() * 0.45f : 0.24f + rng.nextFloat() * 0.32f;
            vx[i] = 0f;
            vy[i] = 0f;
        }

        lastTime = System.currentTimeMillis();
        lastWidth = width;
        lastHeight = height;
        initialized = true;
    }

    public void addRipple(float x, float y) {
        if (shockwaves.size() >= MAX_WAVES) shockwaves.removeFirst();
        shockwaves.addLast(new Shock(x, y, System.currentTimeMillis()));
    }

    public void render(int width, int height, int mx, int my) {
        if (!initialized || width != lastWidth || height != lastHeight) init(width, height);
        if (!NanoVGManager.isInitialized()) NanoVGManager.init();

        long  now     = System.currentTimeMillis();
        float dtSec   = Math.min((now - lastTime) / 1000f, 0.05f);
        lastTime      = now;

        update(dtSec, mx, my, width, height);

        // --- Collect ripple data for shader -----------------------------------------
        int rippleCount = 0;
        if (!shockwaves.isEmpty()) {
            float diag   = (float) Math.hypot(width, height) * 0.18f;
            var arr = shockwaves.toArray(new Shock[0]);
            for (int s = arr.length - 1; s >= 0 && rippleCount < 4; s--) {
                Shock shock = arr[s];
                float ageSec = (now - shock.t0) * 0.001f;
                float r      = WAVE_SPEED * ageSec;
                if (r <= 0 || r > diag) continue;

                float p   = r / diag;
                float str = Math.max(0f, 1f - p) * 0.9f;
                if (str < 0.01f) continue;

                rCx [rippleCount] = shock.x;
                rCy [rippleCount] = shock.y;
                rRad[rippleCount] = r;
                rThi[rippleCount] = WAVE_THICK_START + (WAVE_THICK_END - WAVE_THICK_START) * p;
                rStr[rippleCount] = str;
                rippleCount++;
            }
        }

        // --- Normal NanoVG render ---------------------------------------------------
        var rt   = Minecraft.getInstance().getMainRenderTarget();
        int rtW  = rt.width, rtH = rt.height;
        boolean hasRipples = rippleCount > 0;

        // Disable any active scissor so the full-screen background isn't clipped
        // (e.g. SelectWorldScreen enables scissor for its list widget).
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            // Background color
            nvg.rect(0, 0, width, height, BG);

            // Particles
            int accent = Theme.ACCENT_PRIMARY;
            for (int i = 0; i < N; i++) {
                int col = pstar[i]
                        ? Theme.withAlpha(Theme.blend(0xFFFFFFFF, accent, 0.25f), (int)(pop[i] * 255))
                        : Theme.withAlpha(0xFFFFFFFF, (int)(pop[i] * 255));
                nvg.circle(px[i], py[i], prad[i], col);
            }
        } finally {
            NanoVGManager.endFrame();
        }

        // --- Post-process ripples ---------------------------------------------------
        if (hasRipples) {
            int mcFbo = NanoVGManager.getMainRtFbo();
            rippleEffect.ensureReady(rtW, rtH);

            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

            // Copy scene to offscreen texture
            int prevReadFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, mcFbo);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, rippleEffect.getSceneColorTex());
            org.lwjgl.opengl.GL11.glCopyTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, rtW, rtH);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, prevReadFbo);

            // Shader composite back to main FBO
            rippleEffect.composite(mcFbo, rtW, rtH, rCx, rCy, rRad, rThi, rStr, rippleCount, (float) rtW / width);
        }
    }

    private void update(float dt, int mx, int my, float width, float height) {
        final float R_REPEL   = 140f;
        final float R2_REPEL  = R_REPEL * R_REPEL;
        final float PUSH_BASE = 40f;
        final float velDamp = (float) Math.exp(-VEL_DAMPING * dt);

        long now = System.currentTimeMillis();
        float maxRad = (float) Math.hypot(width, height) * 1.05f;

        // Cleanup shockwaves
        if (!shockwaves.isEmpty()) {
            shockwaves.removeIf(s -> ((now - s.t0) * 0.001f) * WAVE_SPEED > maxRad);
        }

        for (int i = 0; i < N; i++) {
            py[i] -= pspd[i] * dt;

            // Repel
            float dx = px[i] - mx;
            float dy = py[i] - my;
            float d2 = dx * dx + dy * dy;
            if (d2 < R2_REPEL) {
                float d = (float) Math.sqrt(d2);
                float fall = 1f - (d / R_REPEL);
                float push = (PUSH_BASE + 0.35f * pspd[i]) * fall * dt;
                if (d > 1e-4f) {
                    px[i] += (dx / d) * push;
                    py[i] += (dy / d) * push;
                }
            }

            // Shockwaves
            if (!shockwaves.isEmpty()) {
                for (var s : shockwaves) {
                    float age = (now - s.t0) * 0.001f;
                    float R   = WAVE_SPEED * age;
                    if (R <= 2f || R > maxRad) continue;

                    float sx = px[i] - s.x;
                    float sy = py[i] - s.y;
                    float dist = (float) Math.sqrt(sx * sx + sy * sy);
                    float delta = Math.abs(dist - R);
                    if (delta <= WAVE_BAND) {
                        float gf = (float) Math.exp(- (delta * delta) / (0.5f * WAVE_BAND * WAVE_BAND));
                        float nx = (dist > 1e-4f) ? (sx / dist) : 0.7071f;
                        float ny = (dist > 1e-4f) ? (sy / dist) : 0.7071f;
                        float J = WAVE_IMPULSE * gf;
                        vx[i] += nx * J * dt;
                        vy[i] += ny * J * dt;
                    }
                }
            }

            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            vx[i] *= velDamp;
            vy[i] *= velDamp;

            if (py[i] < -4f) {
                py[i] = height + 4f;
                px[i] = rng.nextFloat() * width;
                vx[i] = vy[i] = 0f;
            }
            if (px[i] < -12f)                px[i] += width + 24f;
            else if (px[i] > width + 12f)    px[i] -= width + 24f;
        }
    }
}
