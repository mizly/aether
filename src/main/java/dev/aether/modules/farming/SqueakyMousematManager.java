package dev.aether.modules.farming;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SqueakyMousematManager {
    private static final long CLIENT_THREAD_TIMEOUT_MS = 1000L;
    private static final long SLOT_SWAP_TIMEOUT_MS = 1000L;
    private static final long MOUSEMAT_DELAY_MS = 250L;
    private static final long MOUSEMAT_ROTATION_SETTLE_TIMEOUT_MS = 3000L;
    private static final long MOUSEMAT_ROTATION_SETTLE_POLL_MS = 50L;
    private static final long MOUSEMAT_RETRY_DELAY_MS = 5000L;
    private static final int MAX_MOUSEMAT_ATTEMPTS = 3;
    private static final float ROTATION_MATCH_TOLERANCE_DEGREES = 2f;
    private static final String ITEM_NAME_FRAGMENT = "squeaky mousemat";
    private static final Pattern SELECTED_YAW_PATTERN = Pattern.compile(
            "Selected Yaw:\\s*(-?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECTED_PITCH_PATTERN = Pattern.compile(
            "Selected Pitch:\\s*(-?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static volatile boolean reapplyAttemptArmed = false;

    private SqueakyMousematManager() {
    }

    public static void armReapplyAttempt() {
        reapplyAttemptArmed = true;
    }

    public static void clearReapplyAttempt() {
        reapplyAttemptArmed = false;
    }

    public static boolean shouldUseBeforeFarming(Minecraft client) {
        if (!consumeReapplyAttempt()) {
            return false;
        }
        if (!AetherConfig.SQUEAKY_MOUSEMAT.get() || shouldSkipForPestExchange()) {
            return false;
        }

        MousematSnapshot snapshot = getMousematSnapshot(client);
        return snapshot != null && isCurrentRotationDifferent(client, snapshot);
    }

    private static boolean consumeReapplyAttempt() {
        boolean armed = reapplyAttemptArmed;
        reapplyAttemptArmed = false;
        return armed;
    }

    public static boolean useIfNeeded(Minecraft client) {
        if (!AetherConfig.SQUEAKY_MOUSEMAT.get() || shouldSkipForPestExchange()) {
            return false;
        }

        MousematSnapshot snapshot = getMousematSnapshot(client);
        if (snapshot == null) {
            return false;
        }
        if (!isCurrentRotationDifferent(client, snapshot)) {
            return false;
        }

        client.execute(RotationManager::cancelRotation);
        for (int attempt = 1; attempt <= MAX_MOUSEMAT_ATTEMPTS; attempt++) {
            if (!selectHotbarSlotSync(client, snapshot.slot())) {
                return false;
            }

            if (!sleepMousematDelay(client)) {
                restoreFarmingToolIfExchangeHasPriority(client);
                return false;
            }
            if (shouldSkipForPestExchange()) {
                restoreFarmingToolIfExchangeHasPriority(client);
                return false;
            }

            ClientUtils.performAttackClick();
            FailsafeManager.addRotationGracePeriod(MOUSEMAT_ROTATION_SETTLE_TIMEOUT_MS + MOUSEMAT_DELAY_MS);
            if (waitForMousematRotation(client, snapshot)) {
                FailsafeManager.syncExpectedRotationFromClient(client);
                return true;
            }

            if (attempt >= MAX_MOUSEMAT_ATTEMPTS) {
                break;
            }

            ClientUtils.sendDebugMessage("Mousemat rotation mismatch after use, retrying in 5s (" + attempt + "/" + MAX_MOUSEMAT_ATTEMPTS + ")");
            if (!sleepMousematRetryDelay(client)) {
                restoreFarmingToolIfExchangeHasPriority(client);
                return false;
            }
        }

        ClientUtils.sendDebugMessage("Mousemat rotation mismatch after 3 attempts");
        return false;
    }

    public static RotationSnapshot getMacroRotationOverride(Minecraft client) {
        if (!AetherConfig.SQUEAKY_MOUSEMAT.get() || shouldSkipForPestExchange()) {
            return null;
        }

        MousematSnapshot snapshot = getMousematSnapshot(client);
        if (snapshot == null) {
            return null;
        }

        return queryClientThread(client, () -> {
            if (client.player == null) {
                return null;
            }

            float currentYaw = Mth.wrapDegrees(client.player.getYRot());
            float currentPitch = Mth.clamp(client.player.getXRot(), -90.0f, 90.0f);
            return new RotationSnapshot(currentYaw, currentPitch);
        }, null);
    }

    private static boolean sleepMousematDelay(Minecraft client) {
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING) || shouldSkipForPestExchange()) {
            return false;
        }

        return MacroWorkerThread.sleep(MOUSEMAT_DELAY_MS)
                && !MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)
                && !shouldSkipForPestExchange();
    }

    private static boolean waitForMousematRotation(Minecraft client, MousematSnapshot snapshot) {
        long deadline = System.currentTimeMillis() + MOUSEMAT_ROTATION_SETTLE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING) || shouldSkipForPestExchange()) {
                return false;
            }

            if (!isCurrentRotationDifferent(client, snapshot)) {
                return true;
            }

            if (!MacroWorkerThread.sleep(MOUSEMAT_ROTATION_SETTLE_POLL_MS)) {
                return false;
            }
        }

        return !isCurrentRotationDifferent(client, snapshot);
    }

    private static boolean sleepMousematRetryDelay(Minecraft client) {
        if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING) || shouldSkipForPestExchange()) {
            return false;
        }

        return MacroWorkerThread.sleep(MOUSEMAT_RETRY_DELAY_MS)
                && !MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)
                && !shouldSkipForPestExchange();
    }

    private static boolean shouldSkipForPestExchange() {
        return AutoPestExchangeManager.shouldBlockFarmingResume();
    }

    private static void restoreFarmingToolIfExchangeHasPriority(Minecraft client) {
        if (shouldSkipForPestExchange()) {
            GearManager.swapToFarmingToolSync(client);
        }
    }

    private static boolean selectHotbarSlotSync(Minecraft client, int slot) {
        if (slot < 0 || slot > 8) {
            return false;
        }

        client.execute(() -> FailsafeManager.selectHotbarSlot(client, slot));
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < SLOT_SWAP_TIMEOUT_MS) {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) {
                return false;
            }

            if (getSelectedHotbarSlot(client) == slot) {
                return true;
            }

            if (!MacroWorkerThread.sleep(10)) {
                return false;
            }
        }

        return getSelectedHotbarSlot(client) == slot;
    }

    private static MousematSnapshot getMousematSnapshot(Minecraft client) {
        return queryClientThread(client, () -> {
            if (client.player == null) {
                return null;
            }

            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = client.player.getInventory().getItem(slot);
                if (stack.isEmpty() || !isMousemat(stack)) {
                    continue;
                }

                RotationSnapshot selectedRotation = readSelectedRotation(stack);
                if (selectedRotation == null) {
                    continue;
                }

                return new MousematSnapshot(slot, selectedRotation.yaw(), selectedRotation.pitch());
            }

            return null;
        }, null);
    }

    private static boolean isCurrentRotationDifferent(Minecraft client, MousematSnapshot snapshot) {
        return queryClientThread(client, () -> {
            if (client.player == null) {
                return false;
            }

            float currentYaw = Mth.wrapDegrees(client.player.getYRot());
            float selectedYaw = Mth.wrapDegrees(snapshot.selectedYaw());
            float currentPitch = Mth.clamp(client.player.getXRot(), -90.0f, 90.0f);
            float selectedPitch = Mth.clamp(snapshot.selectedPitch(), -90.0f, 90.0f);
            float yawDiff = Math.abs(Mth.wrapDegrees(currentYaw - selectedYaw));
            float pitchDiff = Math.abs(currentPitch - selectedPitch);

            return yawDiff > ROTATION_MATCH_TOLERANCE_DEGREES
                    || pitchDiff > ROTATION_MATCH_TOLERANCE_DEGREES;
        }, false);
    }

    private static boolean isMousemat(ItemStack stack) {
        String itemName = TablistUtils.stripColors(stack.getHoverName().getString()).toLowerCase();
        return itemName.contains(ITEM_NAME_FRAGMENT);
    }

    private static RotationSnapshot readSelectedRotation(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return null;
        }

        Float selectedYaw = null;
        Float selectedPitch = null;
        for (Component line : lore.lines()) {
            String text = TablistUtils.stripColors(line.getString());
            Matcher yawMatcher = SELECTED_YAW_PATTERN.matcher(text);
            if (yawMatcher.find()) {
                selectedYaw = parseFloat(yawMatcher.group(1));
            }

            Matcher pitchMatcher = SELECTED_PITCH_PATTERN.matcher(text);
            if (pitchMatcher.find()) {
                selectedPitch = parseFloat(pitchMatcher.group(1));
            }
        }

        if (selectedYaw == null || selectedPitch == null) {
            return null;
        }

        return new RotationSnapshot(selectedYaw, selectedPitch);
    }

    private static Float parseFloat(String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int getSelectedHotbarSlot(Minecraft client) {
        return queryClientThread(client, () -> {
            if (client.player == null) {
                return -1;
            }
            return ((AccessorInventory) client.player.getInventory()).getSelected();
        }, -1);
    }

    private static <T> T queryClientThread(Minecraft client, Supplier<T> supplier, T fallback) {
        if (client == null) {
            return fallback;
        }
        if (client.isSameThread()) {
            return supplier.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.complete(fallback);
            }
        });

        try {
            return future.get(CLIENT_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record MousematSnapshot(int slot, float selectedYaw, float selectedPitch) {
    }

    public record RotationSnapshot(float yaw, float pitch) {
    }
}
