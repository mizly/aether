package dev.aether.modules.rotation;

import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanFlickStyleTest {
    private static final long NOW = 1_000_000L;
    private static final HumanFlick.Style PEST = HumanFlick.Style.humanized(90.0, 1.0, 0.06, 0.14, 450.0, 2.0, 0.0);

    @Test
    void aPreciseFlickIsTheOriginalPlanBitForBit() {
        SplittableRandom inputs = new SplittableRandom(7);
        for (int i = 0; i < 20_000; i++) {
            float startYaw = (float) inputs.nextDouble(-720.0, 720.0);
            float startPitch = (float) inputs.nextDouble(-90.0, 90.0);
            float targetYaw = (float) inputs.nextDouble(-720.0, 720.0);
            float targetPitch = (float) inputs.nextDouble(-90.0, 90.0);
            // every fifth turn is a tiny adjustment, which takes the single segment path
            if (i % 5 == 0) {
                targetYaw = startYaw + (float) inputs.nextDouble(-1.2, 1.2);
                targetPitch = Mth.clamp(startPitch + (float) inputs.nextDouble(-0.8, 0.8), -90.0f, 90.0f);
            }
            long seed = inputs.nextLong();
            assertEquals(originalPlan(startYaw, startPitch, targetYaw, targetPitch, NOW, new SplittableRandom(seed)),
                    HumanFlick.plan(startYaw, startPitch, targetYaw, targetPitch, NOW, new SplittableRandom(seed))
                            .segments());
        }
    }

    @Test
    void aPreciseFlickKeepsItsShape() {
        for (int seed = 0; seed < 500; seed++) {
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 175f, 10f, NOW, new SplittableRandom(seed));
            assertEquals(2, plan.segments().size());
            assertEquals(1, plan.strokes());
            long dwell = plan.correction().startMs() - plan.main().endMs();
            assertTrue(dwell >= 15L && dwell < 55L, "dwell " + dwell);
            assertTrue(plan.correction().durationMs() >= 55L && plan.correction().durationMs() < 115L);
            assertTrue(plan.main().durationMs() >= 70L && plan.main().durationMs() <= 320L);
            double along = along(0f, 0f, 175f, 10f, plan.main());
            assertTrue((along >= 0.01 && along <= 0.04) || (along >= -0.07 && along <= -0.02), "along " + along);
        }
    }

    @Test
    void anOvershootLandsPastTheTargetByTheScaledFraction() {
        HumanFlick.Style uncapped = HumanFlick.Style.humanized(90.0, 1.0, 0.06, 0.14, 0.0, 2.0, 0.0);
        SplittableRandom inputs = new SplittableRandom(11);
        for (int i = 0; i < 500; i++) {
            float turn = (float) inputs.nextDouble(95.0, 179.0) * (inputs.nextBoolean() ? 1 : -1);
            float targetPitch = (float) inputs.nextDouble(-30.0, 30.0);
            HumanFlick.Plan plan = HumanFlick.plan(40f, 0f, 40f + turn, targetPitch, NOW, PEST,
                    new SplittableRandom(i));
            // a 450 cap peaks each flick at 315-450 deg/s, which scales the amount by 0.525-0.75
            double along = along(40f, 0f, 40f + turn, targetPitch, landing(plan));
            assertTrue(along >= 0.06 * 0.5 - 1.0e-4 && along <= 0.14 * 0.75 + 1.0e-4, "along " + along);
            assertTrue(plan.correction().startMs() - landing(plan).endMs() >= 70L);

            HumanFlick.Plan fast = HumanFlick.plan(40f, 0f, 40f + turn, targetPitch, NOW, uncapped,
                    new SplittableRandom(i));
            double fastAlong = along(40f, 0f, 40f + turn, targetPitch, landing(fast));
            assertTrue(fastAlong >= 0.06 - 1.0e-4 && fastAlong <= 0.14 + 1.0e-4, "along " + fastAlong);
        }
    }

    @Test
    void theOvershootChanceRampsUpFromHalfTheTurn() {
        HumanFlick.Style always = HumanFlick.Style.humanized(90.0, 1.0, 0.10, 0.14, 0.0, 2.0, 0.0);
        int halfwayOvershoots = 0;
        for (int seed = 0; seed < 500; seed++) {
            assertTrue(alongOf(always, 30f, seed) <= 0.04 + 1.0e-6);
            assertTrue(alongOf(always, 45f, seed) <= 0.04 + 1.0e-6);
            assertTrue(alongOf(always, 90f, seed) >= 0.10 - 1.0e-4);
            assertTrue(alongOf(always, 170f, seed) >= 0.10 - 1.0e-4);
            if (alongOf(always, 67.5f, seed) >= 0.10 - 1.0e-4) {
                halfwayOvershoots++;
            }
        }
        assertEquals(0.5, HumanFlick.overshootChance(always, 67.5), 1.0e-9);
        assertTrue(halfwayOvershoots > 200 && halfwayOvershoots < 300, "overshoots " + halfwayOvershoots);
    }

    @Test
    void aPartialChanceOvershootsOnlyWhenItsRollFires() {
        HumanFlick.Style sometimes = HumanFlick.Style.humanized(90.0, 0.4, 0.10, 0.14, 0.0, 2.0, 0.0);
        int overshoots = 0;
        for (int seed = 0; seed < 1000; seed++) {
            double along = alongOf(sometimes, 150f, seed);
            boolean past = along >= 0.10 - 1.0e-4;
            assertTrue(past || along <= 0.04 + 1.0e-6, "along " + along);
            if (past) {
                overshoots++;
            }
        }
        assertTrue(overshoots > 340 && overshoots < 460, "overshoots " + overshoots);
    }

    @Test
    void zeroChanceNeverSwingsIntoTheOvershootBand() {
        HumanFlick.Style never = HumanFlick.Style.humanized(90.0, 0.0, 0.06, 0.14, 450.0, 2.0, 0.0);
        for (int seed = 0; seed < 500; seed++) {
            assertTrue(alongOf(never, 170f, seed) <= 0.04 + 1.0e-6);
        }
    }

    @Test
    void everyStyleFinishesExactlyOnTheUnwrappedTarget() {
        HumanFlick.Style[] styles = {HumanFlick.Style.PRECISE, PEST,
                HumanFlick.Style.humanized(30.0, 0.5, 0.30, 0.01, 60.0, 1.0, 0.0)};
        for (HumanFlick.Style style : styles) {
            for (int seed = 0; seed < 200; seed++) {
                HumanFlick.Plan plan = HumanFlick.plan(170f, 10f, -160f + seed, 35f, NOW, style,
                        new SplittableRandom(seed));
                float[] end = HumanFlick.sample(plan, plan.endMs() + 5L);
                assertEquals(170f + Mth.wrapDegrees(-160f + seed - 170f), end[0], 1.0e-3f);
                assertEquals(35f, end[1], 1.0e-3f);
            }
        }
    }

    @Test
    void aSpreadStyleSettlesExactlyOnItsOwnPointNearTheTarget() {
        HumanFlick.Style spread = HumanFlick.Style.humanized(90.0, 0.4, 0.05, 0.12, 450.0, 3.0, 0.75);
        double farthest = 0.0;
        for (int seed = 0; seed < 500; seed++) {
            float targetYaw = -160f + seed % 300;
            HumanFlick.Plan plan = HumanFlick.plan(170f, 10f, targetYaw, 35f, NOW, spread, new SplittableRandom(seed));
            HumanFlick.Segment last = plan.segments().getLast();
            float[] end = HumanFlick.sample(plan, plan.endMs());
            assertEquals(last.toYaw(), end[0], 1.0e-3f);
            assertEquals(last.toPitch(), end[1], 1.0e-3f);
            double off = Math.hypot(Mth.wrapDegrees(end[0] - targetYaw), end[1] - 35f);
            assertTrue(off <= 0.75 + 1.0e-3, "off " + off);
            farthest = Math.max(farthest, off);
        }
        assertTrue(farthest > 0.5, "farthest " + farthest);
    }

    @Test
    void aBigMissIsSometimesClosedInTwoGoes() {
        int twoGoes = 0;
        for (int seed = 0; seed < 300; seed++) {
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 178f, 5f, NOW, PEST, new SplittableRandom(seed));
            int corrections = plan.segments().size() - plan.strokes();
            assertTrue(corrections == 1 || corrections == 2, "corrections " + corrections);
            if (corrections == 2) {
                twoGoes++;
                HumanFlick.Segment first = plan.correction();
                double missBefore = Math.hypot(178f - first.fromYaw(), 5f - first.fromPitch());
                double missAfter = Math.hypot(178f - first.toYaw(), 5f - first.toPitch());
                assertTrue(missAfter >= missBefore * 0.08 - 1.0e-3 && missAfter <= missBefore * 0.26,
                        "left " + missAfter);
                long dwell = plan.segments().get(plan.strokes() + 1).startMs() - first.endMs();
                assertTrue(dwell >= 40L && dwell <= 110L, "dwell " + dwell);
            }
            float[] end = HumanFlick.sample(plan, plan.endMs());
            assertEquals(178f, end[0], 1.0e-3f);
            assertEquals(5f, end[1], 1.0e-3f);
        }
        assertTrue(twoGoes > 0 && twoGoes < 300, "two goes " + twoGoes);
    }

    @Test
    void aMissUnderThreeDegreesIsClosedInOneGo() {
        for (int seed = 0; seed < 300; seed++) {
            // a 40 degree turn never overshoots and misses by at most 7.3% of it
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 40f, 0f, NOW, PEST, new SplittableRandom(seed));
            assertEquals(plan.strokes() + 1, plan.segments().size());
        }
    }

    @Test
    void theCameraHoldsStillThroughEveryPause() {
        for (int seed = 0; seed < 50; seed++) {
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 178f, 5f, NOW, PEST, new SplittableRandom(seed));
            for (int i = 0; i + 1 < plan.segments().size(); i++) {
                HumanFlick.Segment done = plan.segments().get(i);
                HumanFlick.Segment next = plan.segments().get(i + 1);
                assertEquals(done.toYaw(), next.fromYaw());
                assertEquals(done.toPitch(), next.fromPitch());
                float[] atEnd = HumanFlick.sample(plan, done.endMs());
                float[] beforeNext = HumanFlick.sample(plan, next.startMs() - 1L);
                assertEquals(done.toYaw(), atEnd[0], 1.0e-3f);
                assertEquals(done.toPitch(), atEnd[1], 1.0e-3f);
                assertEquals(atEnd[0], beforeNext[0], 1.0e-4f);
                assertEquals(atEnd[1], beforeNext[1], 1.0e-4f);
            }
        }
    }

    @Test
    void pitchNeverLeavesThePolesNearVertical() {
        HumanFlick.Style spread = HumanFlick.Style.humanized(90.0, 1.0, 0.06, 0.14, 450.0, 2.0, 1.0);
        SplittableRandom inputs = new SplittableRandom(5);
        for (int seed = 0; seed < 300; seed++) {
            float sign = seed % 2 == 0 ? 1f : -1f;
            float startPitch = sign * (float) inputs.nextDouble(70.0, 90.0);
            float targetPitch = sign * (float) inputs.nextDouble(70.0, 90.0);
            float targetYaw = (float) inputs.nextDouble(-180.0, 180.0);
            for (HumanFlick.Style style : new HumanFlick.Style[] {PEST, spread}) {
                HumanFlick.Plan plan = HumanFlick.plan(0f, startPitch, targetYaw, targetPitch, NOW, style,
                        new SplittableRandom(seed));
                for (HumanFlick.Segment segment : plan.segments()) {
                    assertTrue(Math.abs(segment.toPitch()) <= 90f, "pitch " + segment.toPitch());
                }
                for (long now = NOW; now <= plan.endMs(); now += 2L) {
                    float pitch = HumanFlick.sample(plan, now)[1];
                    assertTrue(pitch >= -90f && pitch <= 90f, "pitch " + pitch);
                }
            }
            HumanFlick.Plan exact = HumanFlick.plan(0f, startPitch, targetYaw, targetPitch, NOW, PEST,
                    new SplittableRandom(seed));
            assertEquals(targetPitch, HumanFlick.sample(exact, exact.endMs())[1], 1.0e-3f);
        }
    }

    @Test
    void theSampledPeakSpeedStaysUnderTheCap() {
        SplittableRandom inputs = new SplittableRandom(13);
        for (int seed = 0; seed < 300; seed++) {
            double cap = inputs.nextDouble(400.0, 1200.0);
            HumanFlick.Style style = HumanFlick.Style.humanized(90.0, 0.5, 0.06, 0.30, cap,
                    inputs.nextDouble(1.0, 8.0), 0.5);
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, (float) inputs.nextDouble(5.0, 179.0),
                    (float) inputs.nextDouble(-60.0, 60.0), NOW, style, new SplittableRandom(seed));
            double peak = peakSpeed(plan);
            assertTrue(peak <= cap, "speed " + peak + " cap " + cap);
        }
    }

    @Test
    void eachFlickPeaksAtItsOwnSpeedUnderTheCap() {
        double[] peaks = new double[500];
        for (int seed = 0; seed < peaks.length; seed++) {
            peaks[seed] = peakSpeed(HumanFlick.plan(0f, 0f, 150f, 10f, NOW, PEST, new SplittableRandom(seed)));
            assertTrue(peaks[seed] <= 450.0, "speed " + peaks[seed]);
        }
        Arrays.sort(peaks);
        double spread = peaks[474] - peaks[25];
        assertTrue(spread >= 40.0, "spread " + spread);
    }

    @Test
    void aLongCappedThrowIsMadeInTwoStrokes() {
        for (int seed = 0; seed < 200; seed++) {
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 178f, 5f, NOW, PEST, new SplittableRandom(seed));
            assertEquals(2, plan.strokes());
            HumanFlick.Segment first = plan.segments().get(0);
            HumanFlick.Segment second = plan.segments().get(1);
            long regrip = second.startMs() - first.endMs();
            assertTrue(regrip >= 60L && regrip <= 160L, "re-grip " + regrip);
            assertEquals(first.toYaw(), second.fromYaw());
            assertEquals(first.toPitch(), second.fromPitch());
            assertTrue(first.bulge() != 0f && second.bulge() != 0f);
            double share = Math.hypot(first.toYaw() - first.fromYaw(), first.toPitch() - first.fromPitch())
                    / Math.hypot(second.toYaw() - first.fromYaw(), second.toPitch() - first.fromPitch());
            assertTrue(share >= 0.49 && share <= 0.66, "share " + share);
            float[] end = HumanFlick.sample(plan, plan.endMs());
            assertEquals(178f, end[0], 1.0e-3f);
            assertEquals(5f, end[1], 1.0e-3f);

            assertEquals(1, HumanFlick.plan(0f, 0f, 40f, 5f, NOW, PEST, new SplittableRandom(seed)).strokes());
        }
    }

    @Test
    void aSlowCapCannotStretchTheTurnPastTheCeilings() {
        HumanFlick.Style slow = HumanFlick.Style.humanized(90.0, 1.0, 0.30, 0.30, 60.0, 2.0, 0.0);
        for (int seed = 0; seed < 50; seed++) {
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 179f, 0f, NOW, slow, new SplittableRandom(seed));
            List<HumanFlick.Segment> strokes = plan.segments().subList(0, plan.strokes());
            long throwMs = strokes.stream().mapToLong(HumanFlick.Segment::durationMs).sum();
            assertTrue(throwMs <= HumanFlick.MAX_CAPPED_MAIN_MS, "throw " + throwMs);
            for (HumanFlick.Segment correction : plan.segments().subList(plan.strokes(), plan.segments().size())) {
                assertTrue(correction.durationMs() <= HumanFlick.MAX_CAPPED_CORRECTION_MS);
            }
        }
    }

    @Test
    void equalBoundsNeverThrow() {
        HumanFlick.Style fixed = HumanFlick.Style.humanized(90.0, 1.0, 0.10, 0.10, 0.0, 2.0, 0.0);
        assertEquals(0.10, alongOf(fixed, 170f, 2), 1.0e-4);
        HumanFlick.Style capped = HumanFlick.Style.humanized(90.0, 1.0, 0.10, 0.10, 450.0, 2.0, 0.0);
        assertDoesNotThrow(() -> HumanFlick.plan(0f, 0f, 170f, 0f, NOW, capped, new SplittableRandom(2)));
    }

    @Test
    void skewedStaysInsideItsBoundsEitherWayRound() {
        SplittableRandom random = new SplittableRandom(17);
        double[] draws = new double[10_000];
        for (int i = 0; i < draws.length; i++) {
            draws[i] = HumanFlick.skewed(random, 2.0, 10.0);
            assertTrue(draws[i] >= 2.0 && draws[i] <= 10.0, "draw " + draws[i]);
        }
        Arrays.sort(draws);
        // the median sits near 38% of the range rather than in its middle
        assertEquals(2.0 + 8.0 * 0.38, draws[draws.length / 2], 0.1);
        for (int seed = 0; seed < 100; seed++) {
            assertEquals(HumanFlick.skewed(new SplittableRandom(seed), 2.0, 10.0),
                    HumanFlick.skewed(new SplittableRandom(seed), 10.0, 2.0));
        }
        assertEquals(4.0, HumanFlick.skewed(random, 4.0, 4.0));
    }

    @Test
    void theHumanizedFactoryNormalisesItsInputs() {
        assertEquals(new HumanFlick.Style(0.0, 1.0, 0.10, 0.30, 0.0, 8.0, 0.0, true),
                HumanFlick.Style.humanized(-10.0, 1.5, 0.30, 0.10, -5.0, 20.0, -1.0));
        HumanFlick.Style narrow = HumanFlick.Style.humanized(90.0, -0.5, 0.05, 0.12, 450.0, 0.2, 0.3);
        assertEquals(0.0, narrow.overshootChance());
        assertEquals(1.0, narrow.targetWidthDegrees());
    }

    @Test
    void theRampFollowsASmoothstep() {
        HumanFlick.Style always = HumanFlick.Style.humanized(90.0, 1.0, 0.10, 0.14, 0.0, 2.0, 0.0);
        assertEquals(0.15625, HumanFlick.overshootChance(always, 56.25), 1.0e-9);
        assertEquals(0.84375, HumanFlick.overshootChance(always, 78.75), 1.0e-9);
    }

    @Test
    void throwsAndCorrectionsAreTimedOnTheTargetWidth() {
        for (double width : new double[] {1.0, 8.0}) {
            HumanFlick.Style style = HumanFlick.Style.humanized(30.0, 1.0, 0.06, 0.14, 0.0, width, 0.0);
            for (int seed = 0; seed < 300; seed++) {
                HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 100f, 5f, NOW, style, new SplittableRandom(seed));
                assertEquals(1, plan.strokes());
                assertFitts(plan.main(), 55.0, 34.0, width, 70L, 320L);
                for (HumanFlick.Segment correction : plan.segments().subList(plan.strokes(), plan.segments().size())) {
                    assertFitts(correction, 45.0, 30.0, width, 55L, 260L);
                }
            }
        }
    }

    @Test
    void noFlickPeaksBelowSeventyPercentOfTheCap() {
        for (int seed = 0; seed < 500; seed++) {
            double peak = peakSpeed(HumanFlick.plan(0f, 0f, 150f, 10f, NOW, PEST, new SplittableRandom(seed)));
            assertTrue(peak >= 0.70 * 450.0 * 0.95, "peak " + peak);
        }
    }

    @Test
    void twoGoCorrectionsGrowMoreLikelyWithTheMiss() {
        HumanFlick.Style fixed = HumanFlick.Style.humanized(30.0, 1.0, 0.10, 0.10, 0.0, 2.0, 0.0);
        int draws = 2000;
        int twoGoes = 0;
        double expected = 0.0;
        for (int seed = 0; seed < draws; seed++) {
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 75f, 0f, NOW, fixed, new SplittableRandom(seed));
            HumanFlick.Segment landing = landing(plan);
            double miss = Math.hypot(75f - landing.toYaw(), landing.toPitch());
            expected += Math.clamp((miss - 3.0) / 9.0, 0.0, 0.85);
            if (plan.segments().size() - plan.strokes() == 2) {
                twoGoes++;
            }
        }
        assertTrue(Math.abs(twoGoes - expected) <= 4.0 * Math.sqrt(draws * 0.25), twoGoes + " vs " + expected);
    }

    @Test
    void onlyThrowsLongerThanASweepAreSplit() {
        SplittableRandom inputs = new SplittableRandom(3);
        for (int seed = 0; seed < 2000; seed++) {
            HumanFlick.Style style = HumanFlick.Style.humanized(90.0, inputs.nextDouble(), 0.06, 0.14,
                    inputs.nextDouble(60.0, 1200.0), inputs.nextDouble(1.0, 8.0), 0.0);
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, (float) inputs.nextDouble(-179.0, 179.0),
                    (float) inputs.nextDouble(-60.0, 60.0), NOW, style, new SplittableRandom(seed));
            if (plan.strokes() == 1) {
                assertTrue(plan.main().durationMs() <= 560L, "single sweep " + plan.main().durationMs());
            }
        }
        HumanFlick.Style never = HumanFlick.Style.humanized(90.0, 0.0, 0.06, 0.14, 450.0, 2.0, 0.0);
        for (int seed = 0; seed < 500; seed++) {
            assertEquals(1, HumanFlick.plan(0f, 0f, 85f, 0f, NOW, never, new SplittableRandom(seed)).strokes());
        }
    }

    @Test
    void aCorrectionNeverOutrunsTheCap() {
        SplittableRandom inputs = new SplittableRandom(21);
        for (int seed = 0; seed < 400; seed++) {
            double cap = inputs.nextDouble(100.0, 400.0);
            HumanFlick.Style style = HumanFlick.Style.humanized(30.0, 1.0, 0.06, 0.30, cap,
                    inputs.nextDouble(1.0, 8.0), 0.0);
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, (float) inputs.nextDouble(40.0, 179.0),
                    (float) inputs.nextDouble(-30.0, 30.0), NOW, style, new SplittableRandom(seed));
            for (HumanFlick.Segment correction : plan.segments().subList(plan.strokes(), plan.segments().size())) {
                if (correction.durationMs() < HumanFlick.MAX_CAPPED_CORRECTION_MS) {
                    double peak = segmentPeak(correction);
                    assertTrue(peak <= cap, "correction " + peak + " cap " + cap);
                }
            }
        }
    }

    @Test
    void theStrokesOfASplitTurnStayUnderACapOneSweepWouldKeep() {
        HumanFlick.Style steady = HumanFlick.Style.humanized(90.0, 0.0, 0.06, 0.14, 250.0, 2.0, 0.0);
        for (int seed = 0; seed < 500; seed++) {
            HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 175f, 0f, NOW, steady, new SplittableRandom(seed));
            assertEquals(2, plan.strokes());
            for (HumanFlick.Segment stroke : plan.segments().subList(0, plan.strokes())) {
                double peak = segmentPeak(stroke);
                assertTrue(peak <= 250.0, "stroke " + peak);
            }
        }
    }

    private static HumanFlick.Segment landing(HumanFlick.Plan plan) {
        return plan.segments().get(plan.strokes() - 1);
    }

    private static double alongOf(HumanFlick.Style style, float turn, long seed) {
        HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, turn, 0f, NOW, style, new SplittableRandom(seed));
        return along(0f, 0f, turn, 0f, landing(plan));
    }

    // how far past the target the segment stops, along the swing and as a share of the turn
    private static double along(float startYaw, float startPitch, float targetYaw, float targetPitch,
                                HumanFlick.Segment segment) {
        double dYaw = Mth.wrapDegrees(targetYaw - startYaw);
        double dPitch = targetPitch - startPitch;
        double endYaw = startYaw + dYaw;
        return ((segment.toYaw() - endYaw) * dYaw + (segment.toPitch() - targetPitch) * dPitch)
                / (dYaw * dYaw + dPitch * dPitch);
    }

    private static double segmentPeak(HumanFlick.Segment segment) {
        return peakSpeed(new HumanFlick.Plan(List.of(segment), 1));
    }

    private static void assertFitts(HumanFlick.Segment segment, double baseMs, double logMs, double width,
                                    long minMs, long maxMs) {
        double length = Math.hypot(segment.toYaw() - segment.fromYaw(), segment.toPitch() - segment.fromPitch());
        double ms = baseMs + logMs * (Math.log(1.0 + length / width) / Math.log(2.0));
        long low = Math.clamp(Math.round(ms * 0.85), minMs, maxMs);
        long high = Math.clamp(Math.round(ms * 1.15), minMs, maxMs);
        assertTrue(segment.durationMs() >= low && segment.durationMs() <= high,
                "width " + width + " length " + length + " took " + segment.durationMs() + " ms");
    }

    private static double peakSpeed(HumanFlick.Plan plan) {
        double peak = 0.0;
        float[] previous = HumanFlick.sample(plan, plan.main().startMs());
        for (long now = plan.main().startMs() + 1L; now <= plan.endMs(); now++) {
            float[] current = HumanFlick.sample(plan, now);
            peak = Math.max(peak, Math.hypot(current[0] - previous[0], current[1] - previous[1]) * 1000.0);
            previous = current;
        }
        return peak;
    }

    // the planner as it was before styles existed, frozen so a precise flick is proven never to drift from it
    private static List<HumanFlick.Segment> originalPlan(float startYaw, float startPitch, float targetYaw,
                                                         float targetPitch, long now, RandomGenerator random) {
        float dYaw = Mth.wrapDegrees(targetYaw - startYaw);
        float dPitch = targetPitch - startPitch;
        float endYaw = startYaw + dYaw;
        double distance = Math.hypot(dYaw, dPitch);
        double ms = 55.0 + 34.0 * (Math.log(1.0 + distance / 2.0) / Math.log(2.0));
        ms *= random.nextDouble(0.85, 1.15);
        long mainMs = Math.clamp(Math.round(ms), 70L, 320L);
        float bulge = (float) (distance * random.nextDouble(0.03, 0.08) * (random.nextBoolean() ? 1 : -1));
        if (distance < 1.5f) {
            return List.of(new HumanFlick.Segment(startYaw, startPitch, endYaw, targetPitch, now, mainMs, bulge));
        }
        double along = random.nextInt(4) == 0 ? random.nextDouble(0.01, 0.04) : -random.nextDouble(0.02, 0.07);
        double side = random.nextDouble(-0.02, 0.02);
        double ux = dYaw / distance;
        double uy = dPitch / distance;
        float missYaw = (float) (endYaw + ux * distance * along - uy * distance * side);
        float missPitch = Mth.clamp((float) (targetPitch + uy * distance * along + ux * distance * side),
                -90.0f, 90.0f);
        HumanFlick.Segment main = new HumanFlick.Segment(startYaw, startPitch, missYaw, missPitch, now, mainMs,
                bulge);
        long dwell = random.nextLong(15L, 55L);
        long correctMs = random.nextLong(55L, 115L);
        return List.of(main, new HumanFlick.Segment(missYaw, missPitch, endYaw, targetPitch, main.endMs() + dwell,
                correctMs, 0.0f));
    }
}
