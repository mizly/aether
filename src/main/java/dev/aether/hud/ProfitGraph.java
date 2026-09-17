package dev.aether.hud;

import dev.aether.modules.profit.SessionProfitHistory;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

final class ProfitGraph {
    private static final double MILLIS_PER_HOUR = 3_600_000.0;
    static final float HEIGHT = 124f;
    private final ProfitGraphScale scale = new ProfitGraphScale();
    private long generation = -1;
    private long lastWindow;
    private float[] vertices = new float[0];

    void render(NVGRenderer nvg, float x, float y, float width,
                SessionProfitHistory.Snapshot snapshot, long windowMillis, long nowNanos) {
        List<RatePoint> points = visibleRates(snapshot, windowMillis);
        double min = points.stream().mapToDouble(RatePoint::coinsPerHour).min().orElse(0);
        double max = points.stream().mapToDouble(RatePoint::coinsPerHour).max().orElse(0);
        var bounds = scale.update(min, max, nowNanos, generation != snapshot.generation() || lastWindow != windowMillis);
        generation = snapshot.generation();
        lastWindow = windowMillis;
        double step = ProfitGraphScale.tickStep(bounds.max() - bounds.min());
        double firstTick = Math.ceil(bounds.min() / step) * step;
        float labelWidth = 0f;
        for (double tick = firstTick; tick <= bounds.max(); tick += step) {
            labelWidth = Math.max(labelWidth,
                    nvg.textWidth(Fonts.MONO, ProfitGraphScale.label(tick, step), 8f));
        }
        float plotX = x + labelWidth + 7f;
        float plotY = y + 23f;
        float plotWidth = width - 2f * (plotX - x);
        float plotHeight = 65f;
        nvg.text(Fonts.REGULAR, AetherLang.localize("Coins per hour"), x, y + 3f, 9f, Theme.HUD_LABEL);

        for (double tick = firstTick; tick <= bounds.max(); tick += step) {
            float tickY = plotY + plotHeight * (1f - (float) bounds.fraction(tick));
            nvg.line(plotX, tickY, plotX + plotWidth, tickY, tick == 0 ? 1f : 0.6f, Theme.HUD_SEP);
            nvg.textRight(Fonts.MONO, ProfitGraphScale.label(tick, step), x, tickY - 4f,
                    labelWidth, 8f, Theme.HUD_LABEL);
        }
        nvg.line(plotX, plotY, plotX, plotY + plotHeight, 0.7f, Theme.HUD_SEP);
        nvg.line(plotX, plotY + plotHeight, plotX + plotWidth, plotY + plotHeight, 0.7f, Theme.HUD_SEP);
        nvg.text(Fonts.MONO, ageLabel(windowMillis), plotX, plotY + plotHeight + 6f, 8f, Theme.HUD_LABEL);
        String middle = ageLabel(windowMillis / 2);
        nvg.textCentered(Fonts.MONO, middle, plotX, plotY + plotHeight + 6f, plotWidth, 10f, 8f, Theme.HUD_LABEL);
        nvg.textRight(Fonts.MONO, "Now", plotX, plotY + plotHeight + 6f, plotWidth, 8f, Theme.HUD_LABEL);
        nvg.textCentered(Fonts.REGULAR, "Active time", plotX, y + 105f, plotWidth, 11f, 8f, Theme.HUD_LABEL);

        int required = points.size() * 2;
        if (vertices.length < required) vertices = new float[required];
        int count = 0;
        double start = snapshot.elapsedMillis() - windowMillis;
        for (var point : points) {
            float px = plotX + (float) ((point.timeMillis() - start) / windowMillis) * plotWidth;
            float py = plotY + (1f - (float) bounds.fraction(point.coinsPerHour())) * plotHeight;
            vertices[count++] = px;
            vertices[count++] = py;
        }
        nvg.pushScissor(plotX, plotY - 1f, plotWidth + 1f, plotHeight + 2f);
        nvg.polyline(vertices, count / 2, 1.5f, Theme.HUD_ACCENT);
        nvg.popScissor();
    }

    static List<RatePoint> visibleRates(SessionProfitHistory.Snapshot snapshot, long windowMillis) {
        double start = Math.max(0, snapshot.elapsedMillis() - windowMillis);
        var points = new ArrayList<RatePoint>();
        long previous = 0;
        for (var point : snapshot.points()) {
            if (point.timeMillis() <= start) {
                previous = point.coins();
            } else {
                if (points.isEmpty()) points.add(ratePoint(start, previous));
                points.add(ratePoint(point.timeMillis(), previous));
                points.add(ratePoint(point.timeMillis(), point.coins()));
                previous = point.coins();
            }
        }
        if (points.isEmpty()) points.add(ratePoint(start, previous));
        points.add(ratePoint(snapshot.elapsedMillis(), previous));
        return points;
    }

    private static RatePoint ratePoint(double timeMillis, long coins) {
        double coinsPerHour = timeMillis > 0 ? coins * MILLIS_PER_HOUR / timeMillis : 0;
        return new RatePoint(timeMillis, coinsPerHour);
    }

    record RatePoint(double timeMillis, double coinsPerHour) {}

    private static String ageLabel(long millis) {
        long seconds = millis / 1_000;
        return seconds % 60 == 0 ? "-" + seconds / 60 + "m" : "-" + seconds / 60 + "m " + seconds % 60 + "s";
    }
}
