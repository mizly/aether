package dev.aether.modules.experiments;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ChronomatronSolver implements ExperimentSolver {
    private static final long INPUT_PHASE_QUIET_MS = 900L;

    private final List<Integer> sequence = new ArrayList<>();
    private final List<Integer> roundSeen = new ArrayList<>();
    private final Set<Integer> litLastTick = new HashSet<>();
    private boolean primed = false;
    private boolean inputPhase = false;
    private int clickIndex = 0;
    private long lastLitActivityAt = 0L;

    @Override
    public void reset() {
        sequence.clear();
        roundSeen.clear();
        litLastTick.clear();
        primed = false;
        inputPhase = false;
        clickIndex = 0;
        lastLitActivityAt = System.currentTimeMillis();
    }

    @Override
    public int nextClick(AbstractContainerScreen<?> screen) {
        long now = System.currentTimeMillis();
        Set<Integer> lit = collectLitSlots(screen);

        if (!primed) {
            primed = true;
            litLastTick.addAll(lit);
            lastLitActivityAt = now;
            return -1;
        }

        if (!inputPhase) {
            for (int slotId : lit) {
                if (!litLastTick.contains(slotId)) {
                    roundSeen.add(slotId);
                }
            }
            if (!lit.isEmpty()) {
                lastLitActivityAt = now;
            }
            litLastTick.clear();
            litLastTick.addAll(lit);

            boolean replayDone = !roundSeen.isEmpty()
                    && lit.isEmpty()
                    && now - lastLitActivityAt >= INPUT_PHASE_QUIET_MS;
            if (replayDone) {
                if (roundSeen.size() >= sequence.size()) {
                    sequence.clear();
                    sequence.addAll(roundSeen);
                }
                roundSeen.clear();
                clickIndex = 0;
                inputPhase = true;
            }
            return -1;
        }

        litLastTick.clear();
        litLastTick.addAll(lit);
        if (clickIndex < sequence.size()) {
            return sequence.get(clickIndex);
        }

        inputPhase = false;
        lastLitActivityAt = now;
        return -1;
    }

    @Override
    public void onClickPerformed(int slot) {
        clickIndex++;
    }

    private static Set<Integer> collectLitSlots(AbstractContainerScreen<?> screen) {
        Set<Integer> lit = new HashSet<>();
        int end = ExperimentSolver.containerSlotCount(screen);
        for (int i = 0; i < end; i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (slot.hasItem() && slot.getItem().hasFoil()) {
                lit.add(i);
            }
        }
        return lit;
    }
}
