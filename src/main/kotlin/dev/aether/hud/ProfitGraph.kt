package dev.aether.hud

import dev.aether.modules.profit.SessionProfitHistory
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang
import java.util.ArrayList

internal class ProfitGraph {
    private val scale = ProfitGraphScale()
    private var generation = -1L
    private var lastWindow = 0L
    private var vertices = FloatArray(0)

    fun render(nvg: NVGRenderer, x: Float, y: Float, width: Float, snapshot: SessionProfitHistory.Snapshot, windowMillis: Long, nowNanos: Long) {
        val points: List<RatePoint> = visibleRates(snapshot, windowMillis)
        val min = points.stream().mapToDouble { it.coinsPerHour() }.min().orElse(0.0)
        val max = points.stream().mapToDouble { it.coinsPerHour() }.max().orElse(0.0)
        val bounds = scale.update(min, max, nowNanos, generation != snapshot.generation() || lastWindow != windowMillis)
        generation = snapshot.generation()
        lastWindow = windowMillis
        val step = ProfitGraphScale.tickStep(bounds.max() - bounds.min())
        val firstTick = Math.ceil(bounds.min() / step) * step
        var labelWidth = 0f
        var tick = firstTick
        while (tick <= bounds.max()) {
            labelWidth = Math.max(labelWidth, nvg.textWidth(Fonts.MONO, ProfitGraphScale.label(tick, step), 8f))
            tick += step
        }
        val plotX = x + labelWidth + 7f
        val plotY = y + 23f
        val plotWidth = width - 2f * (plotX - x)
        val plotHeight = 65f
        nvg.text(Fonts.REGULAR, AetherLang.localize("Coins per hour"), x, y + 3f, 9f, Theme.HUD_LABEL)
        tick = firstTick
        while (tick <= bounds.max()) {
            val tickY = plotY + plotHeight * (1f - bounds.fraction(tick).toFloat())
            nvg.line(plotX, tickY, plotX + plotWidth, tickY, if (tick == 0.0) 1f else 0.6f, Theme.HUD_SEP)
            nvg.textRight(Fonts.MONO, ProfitGraphScale.label(tick, step), x, tickY - 4f, labelWidth, 8f, Theme.HUD_LABEL)
            tick += step
        }
        nvg.line(plotX, plotY, plotX, plotY + plotHeight, 0.7f, Theme.HUD_SEP)
        nvg.line(plotX, plotY + plotHeight, plotX + plotWidth, plotY + plotHeight, 0.7f, Theme.HUD_SEP)
        nvg.text(Fonts.MONO, ageLabel(windowMillis), plotX, plotY + plotHeight + 6f, 8f, Theme.HUD_LABEL)
        val middle = ageLabel(windowMillis / 2)
        nvg.textCentered(Fonts.MONO, middle, plotX, plotY + plotHeight + 6f, plotWidth, 10f, 8f, Theme.HUD_LABEL)
        nvg.textRight(Fonts.MONO, "Now", plotX, plotY + plotHeight + 6f, plotWidth, 8f, Theme.HUD_LABEL)
        nvg.textCentered(Fonts.REGULAR, "Active time", plotX, y + 105f, plotWidth, 11f, 8f, Theme.HUD_LABEL)
        val required = points.size * 2
        if (vertices.size < required) vertices = FloatArray(required)
        var count = 0
        val start = snapshot.elapsedMillis() - windowMillis
        for (point in points) {
            val px = plotX + ((point.timeMillis() - start) / windowMillis).toFloat() * plotWidth
            val py = plotY + (1f - bounds.fraction(point.coinsPerHour()).toFloat()) * plotHeight
            vertices[count++] = px
            vertices[count++] = py
        }
        nvg.pushScissor(plotX, plotY - 1f, plotWidth + 1f, plotHeight + 2f)
        nvg.polyline(vertices, count / 2, 1.5f, Theme.HUD_ACCENT)
        nvg.popScissor()
    }

    class RatePoint(private val timeMillisValue: Double, private val coinsPerHourValue: Double) {
        fun timeMillis(): Double = timeMillisValue
        fun coinsPerHour(): Double = coinsPerHourValue
        override fun equals(other: Any?): Boolean = other is RatePoint && java.lang.Double.compare(timeMillisValue, other.timeMillisValue) == 0 && java.lang.Double.compare(coinsPerHourValue, other.coinsPerHourValue) == 0
        override fun hashCode(): Int = java.util.Objects.hash(timeMillisValue, coinsPerHourValue)
        override fun toString(): String = "RatePoint[timeMillis=$timeMillisValue, coinsPerHour=$coinsPerHourValue]"
    }

    companion object {
        private const val MILLIS_PER_HOUR = 3_600_000.0
        @JvmField
        val HEIGHT = 124f

        @JvmStatic
        fun visibleRates(snapshot: SessionProfitHistory.Snapshot, windowMillis: Long): List<RatePoint> {
            val start = Math.max(0.0, snapshot.elapsedMillis() - windowMillis)
            val points = ArrayList<RatePoint>()
            var previous = 0L
            for (point in snapshot.points()) {
                if (point.timeMillis() <= start) previous = point.coins()
                else {
                    if (points.isEmpty()) points.add(ratePoint(start, previous))
                    points.add(ratePoint(point.timeMillis(), previous))
                    points.add(ratePoint(point.timeMillis(), point.coins()))
                    previous = point.coins()
                }
            }
            if (points.isEmpty()) points.add(ratePoint(start, previous))
            points.add(ratePoint(snapshot.elapsedMillis(), previous))
            return points
        }

        private fun ratePoint(timeMillis: Double, coins: Long): RatePoint {
            val coinsPerHour = if (timeMillis > 0) coins * MILLIS_PER_HOUR / timeMillis else 0.0
            return RatePoint(timeMillis, coinsPerHour)
        }

        private fun ageLabel(millis: Long): String {
            val seconds = millis / 1_000
            return if (seconds % 60 == 0L) "-${seconds / 60}m" else "-${seconds / 60}m ${seconds % 60}s"
        }
    }
}
