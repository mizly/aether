package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import dev.aether.util.PlayerVitals;
import dev.aether.util.SkyblockItems;
import net.minecraft.client.Minecraft;

import java.util.random.RandomGenerator;

// one healing wand click with the line in: swap to the wand, a draw beat, the click, then back to the slot it came from
final class WandHealer {

    // wither impact spends the vitality a wand needs, so right after the blade a heal would fizzle
    private static final long HYPERION_QUIET_MS = 5_000L;
    private static final double DESPERATE_FRACTION = 0.25;
    private static final long CONFIRM_MS = 1_500L;
    private static final int MAX_RETRIES = 3;
    private static final long GIVE_UP_WAIT_MS = 6_000L;
    // the shortest wand heal runs five seconds and heals do not stack, so a click inside it is wasted vitality
    private static final long HEAL_RUNS_MS = 5_000L;
    private static final long TICK_MS = 50L;
    private static final long RESTORE_MIN_MS = 40L;
    private static final long RESTORE_MAX_MS = 130L;
    private static final long LOOKUP_RETRY_MS = 5_000L;

    private enum Phase { IDLE, DRAWN, CLICKED }

    private Phase phase = Phase.IDLE;
    private int wandSlot = -1;
    private int backSlot = -1;
    private long phaseAt;
    private int clickTick;
    private long lastClickAt;
    private double fractionAtClick;
    private boolean pending;
    private int failedTries;
    private long waitUntil;
    private long lookupAt;
    private boolean missingWarned;

    // true when a heal is due and a wand is in the hotbar; the wand is only looked up once health calls for it
    boolean wants(Minecraft mc, long now, boolean lineOut, long lastHyperionClickAt) {
        if (!AetherConfig.FISHING_MACRO_USE_WAND.get()) {
            return false;
        }
        confirm(mc, now);
        if (now < waitUntil || now < lookupAt) {
            return false;
        }
        double threshold = AetherConfig.FISHING_MACRO_HEAL_BELOW_PERCENT.get() / 100.0;
        if (!shouldHeal(fraction(mc, now), threshold, lineOut, now - lastHyperionClickAt, now - lastClickAt)) {
            return false;
        }
        wandSlot = SkyblockItems.findHotbarSlot(mc, SkyblockItems::isHealingWand);
        if (wandSlot >= 0) {
            return true;
        }
        lookupAt = now + LOOKUP_RETRY_MS;
        if (!missingWarned) {
            missingWarned = true;
            ClientUtils.sendMessage("§e" + AetherLang.localize(
                    "Fishing Macro: no healing wand in the hotbar, so it cannot heal."), false);
        }
        return false;
    }

    // true once the wand has been clicked and the old slot is back in hand
    boolean tick(Minecraft mc, long now, int tick, RandomGenerator random) {
        switch (phase) {
            case IDLE -> {
                // a use key still down from the reel would fire the wand on the frame it is drawn
                if (mc.options.keyUse.isDown()) {
                    return false;
                }
                backSlot = FailsafeManager.getCurrentSelectedSlot(mc);
                FailsafeManager.selectHotbarSlot(mc, wandSlot);
                phaseAt = now + AbilitySwapClicker.nextDrawDelayMs(random, AbilitySwapClicker.HEALING_WAND);
                phase = Phase.DRAWN;
            }
            case DRAWN -> {
                if (now < phaseAt) {
                    return false;
                }
                fractionAtClick = fraction(mc, now);
                ClientUtils.performUseClickInstant();
                lastClickAt = now;
                clickTick = tick;
                pending = true;
                phaseAt = now + TICK_MS + random.nextLong(RESTORE_MIN_MS, RESTORE_MAX_MS + 1);
                phase = Phase.CLICKED;
            }
            case CLICKED -> {
                // the click is only handled on a later tick, and the swap back must not beat it there
                if (now < phaseAt || tick <= clickTick) {
                    return false;
                }
                if (backSlot >= 0) {
                    FailsafeManager.selectHotbarSlot(mc, backSlot);
                }
                phase = Phase.IDLE;
                return true;
            }
        }
        return false;
    }

    // a stop mid heal would otherwise leave the wand in hand
    void abort(Minecraft mc) {
        if (phase != Phase.IDLE && backSlot >= 0) {
            FailsafeManager.selectHotbarSlot(mc, backSlot);
        }
        phase = Phase.IDLE;
    }

    void confirm(Minecraft mc, long now) {
        if (!pending || now - lastClickAt < CONFIRM_MS) {
            return;
        }
        pending = false;
        boolean rose = fraction(mc, now) > fractionAtClick;
        failedTries = rose ? 0 : failedTries + 1;
        waitUntil = nextWandAllowedAt(rose, failedTries, lastClickAt, now);
        if (failedTries > MAX_RETRIES) {
            ClientUtils.sendDebugMessage("[FishingMacro] the healing wand is not healing, waiting before trying again");
            failedTries = 0;
        }
    }

    private static double fraction(Minecraft mc, long now) {
        return PlayerVitals.healthFraction(mc, now);
    }

    static boolean shouldHeal(double fraction, double threshold, boolean lineOut, long msSinceHyperion,
                              long msSinceWand) {
        if (lineOut || fraction >= threshold || msSinceWand < CONFIRM_MS) {
            return false;
        }
        return msSinceHyperion >= HYPERION_QUIET_MS || fraction < DESPERATE_FRACTION;
    }

    static long nextWandAllowedAt(boolean rose, int failedTries, long clickAt, long now) {
        if (rose) {
            return clickAt + HEAL_RUNS_MS;
        }
        return failedTries > MAX_RETRIES ? now + GIVE_UP_WAIT_MS : now;
    }
}
