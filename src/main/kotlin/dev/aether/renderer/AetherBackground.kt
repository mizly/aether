package dev.aether.renderer

import dev.aether.ui.theme.Theme
import java.util.ArrayDeque
import java.util.Random

// state lives here so the animation persists across screen switches
class AetherBackground private constructor() {

    companion object {
        @JvmField
        val INSTANCE = AetherBackground()

        // -- Background -------------------------------------------------------------
        private const val BG = 0xFF0A0A0F.toInt()

        // -- Particles --------------------------------------------------------------
        private const val N = 400

        // Frame-rate independent damping
        private const val VEL_DAMPING = 4.2f

        // -- Shockwaves ---------------------------------------------------------------
        private const val MAX_WAVES = 8
        private const val WAVE_SPEED = 200f
        private const val WAVE_BAND = 24f
        private const val WAVE_IMPULSE = 500f
        private const val WAVE_THICK_START = 10f
        private const val WAVE_THICK_END = 4f
    }

    private val px = FloatArray(N)
    private val py = FloatArray(N)
    private val pspd = FloatArray(N)
    private val prad = FloatArray(N)
    private val pop = FloatArray(N)
    private val pstar = BooleanArray(N)
    private val vx = FloatArray(N)
    private val vy = FloatArray(N)

    private val rng = Random()

    private class Shock(val x: Float, val y: Float, val t0: Long)

    private val shockwaves = ArrayDeque<Shock>(MAX_WAVES)

    // -- Ripple effect ---------------------------------------------------------
    private val rippleEffect = RippleEffect()

    // Temp arrays for the GPU blur pass
    private val rCx = FloatArray(4)
    private val rCy = FloatArray(4)
    private val rRad = FloatArray(4)
    private val rThi = FloatArray(4)
    private val rStr = FloatArray(4)

    private var lastTime: Long = 0
    private var initialized = false
    private var lastWidth = 0
    private var lastHeight = 0

    fun init(width: Int, height: Int) {
        for (i in 0 until N) {
            pstar[i] = rng.nextFloat() < 0.15f
            px[i] = rng.nextFloat() * width
            py[i] = rng.nextFloat() * height
            pspd[i] = if (pstar[i]) 5f + rng.nextFloat() * 8f else 8f + rng.nextFloat() * 22f
            prad[i] = if (pstar[i]) 1.2f + rng.nextFloat() * 0.8f else 0.4f + rng.nextFloat() * 0.7f
            pop[i] = if (pstar[i]) 0.30f + rng.nextFloat() * 0.45f else 0.24f + rng.nextFloat() * 0.32f
            vx[i] = 0f
            vy[i] = 0f
        }

        lastTime = System.currentTimeMillis()
        lastWidth = width
        lastHeight = height
        initialized = true
    }

    fun addRipple(x: Float, y: Float) {
        if (shockwaves.size >= MAX_WAVES) shockwaves.removeFirst()
        shockwaves.addLast(Shock(x, y, System.currentTimeMillis()))
    }

    fun render(width: Int, height: Int, mx: Int, my: Int) {
        if (!initialized || width != lastWidth || height != lastHeight) init(width, height)
        if (!NanoVGManager.isInitialized()) NanoVGManager.init()

        val now = System.currentTimeMillis()
        val dtSec = Math.min((now - lastTime) / 1000f, 0.05f)
        lastTime = now

        update(dtSec, mx, my, width.toFloat(), height.toFloat())

        // --- Collect ripple data for shader -----------------------------------------
        var rippleCount = 0
        if (!shockwaves.isEmpty()) {
            val diag = Math.hypot(width.toDouble(), height.toDouble()).toFloat() * 0.18f
            val arr = shockwaves.toTypedArray()
            for (s in arr.size - 1 downTo 0) {
                if (rippleCount >= 4) break
                val shock = arr[s]
                val ageSec = (now - shock.t0) * 0.001f
                val r = WAVE_SPEED * ageSec
                if (r <= 0 || r > diag) continue

                val p = r / diag
                val str = Math.max(0f, 1f - p) * 0.9f
                if (str < 0.01f) continue

                rCx[rippleCount] = shock.x
                rCy[rippleCount] = shock.y
                rRad[rippleCount] = r
                rThi[rippleCount] = WAVE_THICK_START + (WAVE_THICK_END - WAVE_THICK_START) * p
                rStr[rippleCount] = str
                rippleCount++
            }
        }

        if (!NanoVGManager.isInitialized()) NanoVGManager.init()
        val nvg = NanoVGManager.getRenderer() ?: return
        try {
            nvg.rect(0f, 0f, width.toFloat(), height.toFloat(), BG)

            val accent = Theme.ACCENT_PRIMARY
            for (i in 0 until N) {
                val col = if (pstar[i])
                    Theme.withAlpha(Theme.blend(0xFFFFFFFF.toInt(), accent, 0.25f), (pop[i] * 255).toInt())
                else
                    Theme.withAlpha(0xFFFFFFFF.toInt(), (pop[i] * 255).toInt())
                nvg.circle(px[i], py[i], prad[i], col)
            }
        } finally {
            NanoVGManager.endFrame()
        }

        if (rippleCount > 0) {
            val target = NanoVGManager.mainTarget()
            if (target != null) {
                rippleEffect.composite(target, rCx, rCy, rRad, rThi, rStr, rippleCount,
                        NanoVGManager.getPxRatio())
            }
        }
    }

    private fun update(dt: Float, mx: Int, my: Int, width: Float, height: Float) {
        val R_REPEL = 140f
        val R2_REPEL = R_REPEL * R_REPEL
        val PUSH_BASE = 40f
        val velDamp = Math.exp((-VEL_DAMPING * dt).toDouble()).toFloat()

        val now = System.currentTimeMillis()
        val maxRad = Math.hypot(width.toDouble(), height.toDouble()).toFloat() * 1.05f

        // Cleanup shockwaves
        if (!shockwaves.isEmpty()) {
            shockwaves.removeIf { s -> ((now - s.t0) * 0.001f) * WAVE_SPEED > maxRad }
        }

        for (i in 0 until N) {
            py[i] -= pspd[i] * dt

            // Repel
            val dx = px[i] - mx
            val dy = py[i] - my
            val d2 = dx * dx + dy * dy
            if (d2 < R2_REPEL) {
                val d = Math.sqrt(d2.toDouble()).toFloat()
                val fall = 1f - (d / R_REPEL)
                val push = (PUSH_BASE + 0.35f * pspd[i]) * fall * dt
                if (d > 1e-4f) {
                    px[i] += (dx / d) * push
                    py[i] += (dy / d) * push
                }
            }

            // Shockwaves
            if (!shockwaves.isEmpty()) {
                for (s in shockwaves) {
                    val age = (now - s.t0) * 0.001f
                    val R = WAVE_SPEED * age
                    if (R <= 2f || R > maxRad) continue

                    val sx = px[i] - s.x
                    val sy = py[i] - s.y
                    val dist = Math.sqrt((sx * sx + sy * sy).toDouble()).toFloat()
                    val delta = Math.abs(dist - R)
                    if (delta <= WAVE_BAND) {
                        val gf = Math.exp((-(delta * delta) / (0.5f * WAVE_BAND * WAVE_BAND)).toDouble()).toFloat()
                        val nx = if (dist > 1e-4f) (sx / dist) else 0.7071f
                        val ny = if (dist > 1e-4f) (sy / dist) else 0.7071f
                        val J = WAVE_IMPULSE * gf
                        vx[i] += nx * J * dt
                        vy[i] += ny * J * dt
                    }
                }
            }

            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            vx[i] *= velDamp
            vy[i] *= velDamp

            if (py[i] < -4f) {
                py[i] = height + 4f
                px[i] = rng.nextFloat() * width
                vx[i] = 0f
                vy[i] = 0f
            }
            if (px[i] < -12f) px[i] += width + 24f
            else if (px[i] > width + 12f) px[i] -= width + 24f
        }
    }
}