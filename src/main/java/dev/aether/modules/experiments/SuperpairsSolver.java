package dev.aether.modules.experiments;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SuperpairsSolver implements ExperimentSolver {
    private final Map<Integer, String> known = new HashMap<>();
    private final Set<Integer> matched = new HashSet<>();
    private int firstPick = -1;
    private String firstPickName = null;

    @Override
    public void reset() {
        known.clear();
        matched.clear();
        firstPick = -1;
        firstPickName = null;
    }

    @Override
    public int nextClick(AbstractContainerScreen<?> screen) {
        Map<Integer, String> revealed = observeBoard(screen);

        if (firstPick != -1 && revealed.containsKey(firstPick)) {
            firstPickName = revealed.get(firstPick);
        }

        if (firstPick == -1) {
            int pairSlot = findCoveredKnownPairSlot(screen);
            if (pairSlot != -1) {
                return pairSlot;
            }
            return firstCoveredUnknown(screen);
        }

        if (firstPickName == null) {
            return -1;
        }

        for (Map.Entry<Integer, String> entry : known.entrySet()) {
            int slot = entry.getKey();
            if (slot != firstPick
                    && !matched.contains(slot)
                    && entry.getValue().equals(firstPickName)
                    && isCoveredSlot(screen, slot)) {
                return slot;
            }
        }
        return firstCoveredUnknown(screen);
    }

    @Override
    public void onClickPerformed(int slot) {
        if (firstPick == -1) {
            firstPick = slot;
            firstPickName = known.get(slot);
        } else {
            firstPick = -1;
            firstPickName = null;
        }
    }

    private Map<Integer, String> observeBoard(AbstractContainerScreen<?> screen) {
        Map<Integer, String> revealed = new HashMap<>();
        Map<String, Integer> seenNames = new HashMap<>();
        int end = ExperimentSolver.containerSlotCount(screen);
        for (int i = 0; i < end; i++) {
            String name = ExperimentSolver.slotName(screen, i);
            if (name.isEmpty() || isCoverName(name)) {
                continue;
            }

            revealed.put(i, name);
            known.put(i, name);
            Integer other = seenNames.put(name, i);
            if (other != null) {
                matched.add(other);
                matched.add(i);
            }
        }
        return revealed;
    }

    private int findCoveredKnownPairSlot(AbstractContainerScreen<?> screen) {
        Map<String, Integer> coveredByName = new HashMap<>();
        for (Map.Entry<Integer, String> entry : known.entrySet()) {
            int slot = entry.getKey();
            if (matched.contains(slot) || !isCoveredSlot(screen, slot)) {
                continue;
            }
            Integer other = coveredByName.put(entry.getValue(), slot);
            if (other != null) {
                return other;
            }
        }
        return -1;
    }

    private int firstCoveredUnknown(AbstractContainerScreen<?> screen) {
        int end = ExperimentSolver.containerSlotCount(screen);
        for (int i = 0; i < end; i++) {
            if (i != firstPick && !known.containsKey(i) && !matched.contains(i) && isCoveredSlot(screen, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isCoveredSlot(AbstractContainerScreen<?> screen, int slot) {
        if (slot < 0 || slot >= ExperimentSolver.containerSlotCount(screen)) {
            return false;
        }
        return isCoverName(ExperimentSolver.slotName(screen, slot));
    }

    private static boolean isCoverName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return name.equals("?") || lower.contains("click any button") || lower.contains("click a button");
    }
}
