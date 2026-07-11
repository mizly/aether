package dev.aether.modules.experiments;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class UltrasequencerSolver implements ExperimentSolver {
    private static final long INPUT_PHASE_QUIET_MS = 600L;

    private final Map<Integer, Integer> numberedSlots = new HashMap<>();
    private final List<Integer> clickOrder = new ArrayList<>();
    private boolean inputPhase = false;
    private int clickIndex = 0;
    private long lastDigitsSeenAt = 0L;

    @Override
    public void reset() {
        numberedSlots.clear();
        clickOrder.clear();
        inputPhase = false;
        clickIndex = 0;
        lastDigitsSeenAt = System.currentTimeMillis();
    }

    @Override
    public int nextClick(AbstractContainerScreen<?> screen) {
        long now = System.currentTimeMillis();

        if (!inputPhase) {
            boolean digitsVisible = false;
            int end = ExperimentSolver.containerSlotCount(screen);
            for (int i = 0; i < end; i++) {
                String name = ExperimentSolver.slotName(screen, i);
                if (!name.isEmpty() && name.chars().allMatch(Character::isDigit)) {
                    numberedSlots.put(i, Integer.parseInt(name));
                    digitsVisible = true;
                }
            }

            if (digitsVisible) {
                lastDigitsSeenAt = now;
                return -1;
            }
            if (!numberedSlots.isEmpty() && now - lastDigitsSeenAt >= INPUT_PHASE_QUIET_MS) {
                clickOrder.clear();
                numberedSlots.entrySet().stream()
                        .sorted(Comparator.comparingInt(Map.Entry::getValue))
                        .forEach(entry -> clickOrder.add(entry.getKey()));
                clickIndex = 0;
                inputPhase = true;
            }
            return -1;
        }

        if (clickIndex < clickOrder.size()) {
            return clickOrder.get(clickIndex);
        }

        inputPhase = false;
        numberedSlots.clear();
        clickOrder.clear();
        lastDigitsSeenAt = System.currentTimeMillis();
        return -1;
    }

    @Override
    public void onClickPerformed(int slot) {
        clickIndex++;
    }
}
