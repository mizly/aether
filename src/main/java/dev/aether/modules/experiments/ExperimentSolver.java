package dev.aether.modules.experiments;

import dev.aether.util.TablistUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

interface ExperimentSolver {
    void reset();

    /** Returns the container slot to click next, or -1 when there is nothing to do yet. */
    int nextClick(AbstractContainerScreen<?> screen);

    void onClickPerformed(int slot);

    static int containerSlotCount(AbstractContainerScreen<?> screen) {
        return Math.max(0, screen.getMenu().slots.size() - 36);
    }

    static String slotName(AbstractContainerScreen<?> screen, int index) {
        Slot slot = screen.getMenu().slots.get(index);
        if (!slot.hasItem()) {
            return "";
        }
        return TablistUtils.stripColors(slot.getItem().getHoverName().getString()).trim();
    }
}
