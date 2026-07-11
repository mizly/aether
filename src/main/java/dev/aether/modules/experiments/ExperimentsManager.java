package dev.aether.modules.experiments;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.mixin.AccessorAbstractContainerScreen;
import dev.aether.mixin.MixinMinecraft;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExperimentsManager {
    private static final String MAIN_MENU_TITLE = "experimentation table";
    private static final double MAX_TABLE_RANGE_SQ = 4.5 * 4.5;
    private static final int TABLE_SCAN_RADIUS = 6;
    private static final long MENU_TIMEOUT_MS = 5_000L;
    private static final long DEBUG_OPEN_TIMEOUT_MS = 60_000L;
    private static final long STALL_TIMEOUT_MS = 45_000L;
    private static final long REOPEN_DELAY_MS = 800L;
    private static final long ROTATION_MS = 300L;
    private static final int MAX_XP_BUYS = 3;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final String[] TIER_NAMES = {
            "Beginner", "High", "Grand", "Supreme", "Transcendent", "Metaphysical"};
    private static final String[] XP_BOTTLE_PREFERENCE = {
            "Titanic Experience Bottle", "Grand Experience Bottle", "Experience Bottle"};

    // Mini-experiments first, Superpairs last.
    public enum Experiment {
        CHRONOMATRON("Chronomatron"),
        ULTRASEQUENCER("Ultrasequencer"),
        SUPERPAIRS("Superpairs");

        final String menuName;

        Experiment(String menuName) {
            this.menuName = menuName;
        }
    }

    private static volatile boolean running = false;
    private static volatile boolean opening = false;
    private static volatile boolean playing = false;
    private static volatile boolean pendingXpBuy = false;
    private static volatile boolean awaitingXpMenu = false;
    private static volatile boolean xpBottleBought = false;
    private static volatile int xpBuysThisRun = 0;
    private static volatile long nextActionAtMs = 0L;
    private static volatile long lastProgressAtMs = 0L;
    private static volatile BlockPos tablePos = null;
    private static volatile Experiment activeExperiment = null;
    private static volatile ExperimentSolver solver = null;
    private static volatile PendingClick pendingClick = null;
    private static final Set<Experiment> completed = EnumSet.noneOf(Experiment.class);
    private static String lastDebugLine = null;
    private static String lastLoggedTitle = null;

    private ExperimentsManager() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static void toggle(Minecraft client) {
        if (running) {
            stop("§eAuto Experiments stopped.");
        } else {
            start(client);
        }
    }

    public static void toggleDebug() {
        boolean enabled = !AetherConfig.AUTO_EXPERIMENTS_DEBUG.get();
        AetherConfig.AUTO_EXPERIMENTS_DEBUG.set(enabled);
        if (enabled && AetherConfig.AUTO_EXPERIMENTS_STEP.get()) {
            AetherConfig.AUTO_EXPERIMENTS_STEP.set(false);
            ClientUtils.sendMessage("§7(step mode turned off - debug mode never clicks)", false);
        }
        AetherConfig.save();
        if (enabled) {
            ClientUtils.sendMessage("§eExperiments debug mode §aON§7 - the mod will only observe and log. "
                    + "No clicks, rotations or interactions are sent; open the table and play manually to verify the solver.", false);
        } else {
            ClientUtils.sendMessage("§eExperiments debug mode §cOFF§7 - clicks will be performed normally.", false);
        }
    }

    public static void toggleStep() {
        boolean enabled = !AetherConfig.AUTO_EXPERIMENTS_STEP.get();
        AetherConfig.AUTO_EXPERIMENTS_STEP.set(enabled);
        if (enabled && AetherConfig.AUTO_EXPERIMENTS_DEBUG.get()) {
            AetherConfig.AUTO_EXPERIMENTS_DEBUG.set(false);
            ClientUtils.sendMessage("§7(debug mode turned off - step mode sends real clicks)", false);
        }
        AetherConfig.save();
        if (enabled) {
            ClientUtils.sendMessage("§eExperiments step mode §aON§7 - the macro pauses before every click. "
                    + "Run §f/aether experiments click§7 to send each one, so you control the pacing.", false);
        } else {
            ClientUtils.sendMessage("§eExperiments step mode §cOFF§7 - clicks are sent automatically.", false);
        }
    }

    public static void confirmPendingClick(Minecraft client) {
        PendingClick pending = pendingClick;
        if (pending == null) {
            ClientUtils.sendMessage("§eNo click is pending.", false);
            return;
        }
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || pending.slot() >= screen.getMenu().slots.size()) {
            pendingClick = null;
            ClientUtils.sendMessage("§cThe menu changed; pending click discarded.", false);
            return;
        }

        pendingClick = null;
        markProgress();
        nextActionAtMs = System.currentTimeMillis() + ClientUtils.getGuiClickDelayMs(false);
        ClientUtils.performSlotClick(screen, pending.slot(), 0, ContainerInput.PICKUP);
        ClientUtils.sendMessage("§aStep: clicked slot " + pending.slot() + " §7(" + pending.reason() + ")", false);
        if (pending.onSent() != null) {
            pending.onSent().run();
        }
    }

    public static void testClick(Minecraft client, int slot) {
        if (client == null || client.player == null) {
            return;
        }
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            ClientUtils.sendMessage("§cNo container menu is open. Tip: you can't type commands inside a GUI - "
                    + "hover a slot and press the Experiment Click keybind instead.", false);
            return;
        }
        if (slot < 0 || slot >= screen.getMenu().slots.size()) {
            ClientUtils.sendMessage("§cSlot " + slot + " is out of range - this menu has "
                    + screen.getMenu().slots.size() + " slots.", false);
            return;
        }

        String name = ExperimentSolver.slotName(screen, slot);
        ClientUtils.performSlotClick(screen, slot, 0, ContainerInput.PICKUP);
        ClientUtils.sendMessage("§aTest click sent on slot " + slot
                + (name.isEmpty() ? "" : " §7(" + name + ")"), false);
    }

    /** Fired from the GUI keybind: confirms a pending step click, otherwise test-clicks the hovered slot. */
    public static void onGuiKeybind(Minecraft client, AbstractContainerScreen<?> screen) {
        if (client == null || client.player == null) {
            return;
        }

        if (pendingClick != null) {
            confirmPendingClick(client);
            return;
        }

        Slot hovered = ((AccessorAbstractContainerScreen) screen).getHoveredSlot();
        if (hovered == null) {
            ClientUtils.sendMessage("§eHover over a slot, then press the key to test-click it.", false);
            return;
        }

        int listIndex = screen.getMenu().slots.indexOf(hovered);
        String name = listIndex >= 0 ? ExperimentSolver.slotName(screen, listIndex) : "";
        ((AccessorAbstractContainerScreen) screen).invokeSlotClicked(
                hovered, hovered.index, 0, ContainerInput.PICKUP);
        ClientUtils.sendMessage("§aTest click sent on slot " + listIndex
                + (name.isEmpty() ? "" : " §7(" + name + ")"), false);
    }

    public static synchronized void start(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        if (running) {
            ClientUtils.sendMessage("§cAuto Experiments is already running.", false);
            return;
        }

        BlockPos table = findNearbyTable(client);
        if (table == null) {
            ClientUtils.sendMessage("§cError: no Experimentation Table found nearby.", false);
            return;
        }
        if (client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(table)) > MAX_TABLE_RANGE_SQ) {
            ClientUtils.sendMessage("§cError: you are not within range of the Experimentation Table.", false);
            return;
        }

        resetState();
        tablePos = table;
        running = true;
        markProgress();
        ClientUtils.sendMessage("§eStarting Auto Experiments..."
                + (isDebug() ? " §b(debug mode: observe only, no input is sent)" : ""), false);
        openTableAsync(0L);
    }

    public static synchronized void stop(String message) {
        if (!running) {
            return;
        }
        running = false;
        RotationManager.cancelRotation();
        resetState();
        if (message != null) {
            ClientUtils.sendMessage(message, false);
        }
    }

    public static void handleMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!running || client.player == null) {
            return;
        }

        opening = false;
        String title = TablistUtils.stripColors(screen.getTitle().getString()).trim();
        logTitleOnce(title);
        if (System.currentTimeMillis() < nextActionAtMs) {
            return;
        }

        String lowerTitle = title.toLowerCase(Locale.ROOT);
        if (awaitingXpMenu) {
            handleXpMenu(client, screen, lowerTitle);
            return;
        }
        if (lowerTitle.contains(MAIN_MENU_TITLE)) {
            handleMainMenu(client, screen);
            return;
        }

        Experiment experiment = experimentForTitle(lowerTitle);
        if (experiment == null) {
            debugLog("Ignoring unknown menu '" + title + "'");
            return;
        }
        if (hasTierItems(screen)) {
            if (!playing) {
                handleTierSelect(client, screen, experiment);
            }
            return;
        }
        if (playing && experiment == activeExperiment) {
            handleGame(screen, experiment);
        }
    }

    public static void update() {
        if (!running) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            stop(null);
            return;
        }

        if (client.screen == null) {
            pendingClick = null;
            if (playing) {
                onExperimentFinished();
            }
            if (!opening) {
                if (nextExperiment() == null) {
                    stop("§aAuto Experiments finished.");
                    return;
                }
                openTableAsync(REOPEN_DELAY_MS);
            }
        }

        if (!isDebug() && pendingClick == null
                && System.currentTimeMillis() - lastProgressAtMs > STALL_TIMEOUT_MS) {
            stop("§cAuto Experiments stalled for too long. Stopping.");
        }
    }

    private static void handleMainMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        markProgress();
        if (playing) {
            onExperimentFinished();
        }

        if (pendingXpBuy) {
            int bottleSlot = findXpBottleSlot(screen);
            if (bottleSlot == -1) {
                stop("§cError: could not find the XP bottle in the Experimentation Table menu.");
                return;
            }
            awaitingXpMenu = true;
            xpBottleBought = false;
            performClick(screen, bottleSlot, "open XP bottle shop");
            return;
        }

        Experiment next = nextExperiment();
        if (next == null) {
            stop("§aAuto Experiments finished.");
            if (!isDebug()) {
                client.player.closeContainer();
            }
            return;
        }

        int slot = findSlotByName(screen, next.menuName);
        if (slot == -1) {
            debugLog(next.menuName + " not found in the table menu; skipping it.");
            completed.add(next);
            return;
        }
        activeExperiment = next;
        playing = false;
        performClick(screen, slot, "open " + next.menuName);
    }

    private static void handleTierSelect(Minecraft client, AbstractContainerScreen<?> screen, Experiment experiment) {
        markProgress();
        activeExperiment = experiment;

        List<TierOption> tiers = parseTiers(client, screen);
        if (tiers.isEmpty()) {
            debugLog("No tier options parsed in the " + experiment.menuName + " menu.");
            return;
        }

        int playerLevels = client.player.experienceLevel;
        TierOption best = null;
        boolean anyXpBlocked = false;
        for (TierOption tier : tiers) {
            if (tier.onCooldown()) {
                continue;
            }
            if (tier.requiredLevels() <= playerLevels) {
                if (best == null || tier.rank() > best.rank()) {
                    best = tier;
                }
            } else {
                anyXpBlocked = true;
            }
        }

        if (best != null) {
            playing = true;
            solver = createSolver(experiment);
            solver.reset();
            debugLog("Starting " + experiment.menuName + " tier '" + best.name()
                    + "' (needs " + best.requiredLevels() + " levels, have " + playerLevels + ").");
            performClick(screen, best.slot(), "start tier " + best.name());
            return;
        }

        if (anyXpBlocked) {
            if (xpBuysThisRun >= MAX_XP_BUYS) {
                stop("§cError: still not enough XP after buying bottles. Stopping.");
                return;
            }
            pendingXpBuy = true;
            debugLog("Not enough XP for " + experiment.menuName
                    + " (" + playerLevels + " levels). Going back to buy XP.");
            goBack(client, screen);
            return;
        }

        completed.add(experiment);
        activeExperiment = null;
        debugLog(experiment.menuName + " is unavailable (cooldown). Skipping it.");
        goBack(client, screen);
    }

    private static void handleXpMenu(Minecraft client, AbstractContainerScreen<?> screen, String lowerTitle) {
        markProgress();
        if (!xpBottleBought) {
            if (lowerTitle.contains(MAIN_MENU_TITLE)) {
                return;
            }
            int bottleSlot = findBestXpPurchaseSlot(screen);
            if (bottleSlot == -1) {
                stop("§cError: no XP bottles found to buy.");
                return;
            }
            xpBottleBought = true;
            xpBuysThisRun++;
            performClick(screen, bottleSlot, "buy XP bottle");
            return;
        }

        awaitingXpMenu = false;
        pendingXpBuy = false;
        xpBottleBought = false;
        goBack(client, screen);
    }

    private static void handleGame(AbstractContainerScreen<?> screen, Experiment experiment) {
        markProgress();
        ExperimentSolver activeSolver = solver;
        if (activeSolver == null) {
            return;
        }

        int slot = activeSolver.nextClick(screen);
        if (slot < 0 || slot >= screen.getMenu().slots.size()) {
            return;
        }
        performClick(screen, slot, experiment.menuName + " slot " + slot, () -> {
            ExperimentSolver current = solver;
            if (current != null) {
                current.onClickPerformed(slot);
            }
        });
    }

    private static void onExperimentFinished() {
        Experiment finished = activeExperiment;
        if (finished != null) {
            completed.add(finished);
            ClientUtils.sendMessage("§a" + finished.menuName + " done.", false);
        }
        activeExperiment = null;
        playing = false;
        solver = null;
        markProgress();
    }

    private static void openTableAsync(long delayMs) {
        opening = true;
        MacroWorkerThread.getInstance().submit("AutoExperiments-Open", () -> {
            if (delayMs > 0) {
                MacroWorkerThread.sleep((int) delayMs);
            }
            Minecraft client = Minecraft.getInstance();
            if (shouldAbort(client)) {
                return;
            }

            if (isDebug()) {
                ClientUtils.sendMessage("§bDebug: right-click the Experimentation Table yourself now.", false);
                if (!waitForContainerScreen(DEBUG_OPEN_TIMEOUT_MS)) {
                    stop("§cDebug: no menu was opened within 60s. Stopping.");
                }
                return;
            }

            BlockPos table = tablePos;
            for (int attempt = 0; attempt < 3 && !shouldAbort(client); attempt++) {
                if (!isLookingAt(client, table)) {
                    client.execute(() -> RotationManager.initiateRotation(
                            client, Vec3.atCenterOf(table), ROTATION_MS));
                    waitForRotation();
                }
                if (shouldAbort(client)) {
                    return;
                }
                client.execute(() -> ((MixinMinecraft) client).aether$startUseItem());
                if (waitForContainerScreen(MENU_TIMEOUT_MS)) {
                    markProgress();
                    return;
                }
            }
            stop("§cError: could not open the Experimentation Table.");
        });
    }

    private static void goBack(Minecraft client, AbstractContainerScreen<?> screen) {
        int back = findSlotByName(screen, "Go Back");
        if (back != -1) {
            performClick(screen, back, "go back");
            return;
        }
        if (isDebug()) {
            debugLog("Would close the menu and reopen the table.");
            return;
        }
        client.player.closeContainer();
        openTableAsync(REOPEN_DELAY_MS);
    }

    private static void performClick(AbstractContainerScreen<?> screen, int slot, String reason) {
        performClick(screen, slot, reason, null);
    }

    private static void performClick(AbstractContainerScreen<?> screen, int slot, String reason, Runnable onSent) {
        markProgress();
        if (isDebug()) {
            nextActionAtMs = System.currentTimeMillis() + ClientUtils.getGuiClickDelayMs(false);
            debugLog("Would click slot " + slot + " §8(" + reason + ")");
            if (onSent != null) {
                onSent.run();
            }
            return;
        }

        if (isStep()) {
            PendingClick current = pendingClick;
            if (current == null || current.slot() != slot || !current.reason().equals(reason)) {
                pendingClick = new PendingClick(slot, reason, onSent);
                ClientUtils.sendMessage("§eStep: pending click on slot " + slot + " §7(" + reason
                        + ")§e. Press the Experiment Click key to send it.", false);
            }
            return;
        }

        nextActionAtMs = System.currentTimeMillis() + ClientUtils.getGuiClickDelayMs(false);
        ClientUtils.performSlotClick(screen, slot, 0, ContainerInput.PICKUP);
        if (onSent != null) {
            onSent.run();
        }
    }

    private static List<TierOption> parseTiers(Minecraft client, AbstractContainerScreen<?> screen) {
        List<TierOption> tiers = new ArrayList<>();
        int end = ExperimentSolver.containerSlotCount(screen);
        for (int i = 0; i < end; i++) {
            int rank = tierRank(ExperimentSolver.slotName(screen, i));
            if (rank < 0) {
                continue;
            }

            int requiredLevels = 0;
            boolean onCooldown = false;
            for (Component line : loreOf(client, screen, i)) {
                String text = TablistUtils.stripColors(line.getString()).toLowerCase(Locale.ROOT);
                if (text.contains("cooldown") || text.contains("available in")) {
                    onCooldown = true;
                }
                if (text.contains("level")) {
                    Matcher matcher = NUMBER_PATTERN.matcher(text);
                    while (matcher.find()) {
                        requiredLevels = Math.max(requiredLevels, Integer.parseInt(matcher.group(1)));
                    }
                }
            }
            tiers.add(new TierOption(i, ExperimentSolver.slotName(screen, i), rank, requiredLevels, onCooldown));
        }
        return tiers;
    }

    private static boolean hasTierItems(AbstractContainerScreen<?> screen) {
        int count = 0;
        int end = ExperimentSolver.containerSlotCount(screen);
        for (int i = 0; i < end; i++) {
            if (tierRank(ExperimentSolver.slotName(screen, i)) >= 0) {
                count++;
            }
        }
        return count >= 3;
    }

    private static int tierRank(String name) {
        for (int rank = 0; rank < TIER_NAMES.length; rank++) {
            if (name.regionMatches(true, 0, TIER_NAMES[rank], 0, TIER_NAMES[rank].length())) {
                return rank;
            }
        }
        return -1;
    }

    private static List<Component> loreOf(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex) {
        Slot slot = screen.getMenu().slots.get(slotIndex);
        if (!slot.hasItem() || client.player == null) {
            return List.of();
        }
        return slot.getItem().getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL);
    }

    private static Experiment experimentForTitle(String lowerTitle) {
        for (Experiment experiment : Experiment.values()) {
            if (lowerTitle.contains(experiment.menuName.toLowerCase(Locale.ROOT))) {
                return experiment;
            }
        }
        return null;
    }

    private static Experiment nextExperiment() {
        for (Experiment experiment : Experiment.values()) {
            if (!completed.contains(experiment)) {
                return experiment;
            }
        }
        return null;
    }

    private static ExperimentSolver createSolver(Experiment experiment) {
        return switch (experiment) {
            case CHRONOMATRON -> new ChronomatronSolver();
            case ULTRASEQUENCER -> new UltrasequencerSolver();
            case SUPERPAIRS -> new SuperpairsSolver();
        };
    }

    private static int findSlotByName(AbstractContainerScreen<?> screen, String namePart) {
        String target = namePart.toLowerCase(Locale.ROOT);
        int end = ExperimentSolver.containerSlotCount(screen);
        for (int i = 0; i < end; i++) {
            if (ExperimentSolver.slotName(screen, i).toLowerCase(Locale.ROOT).contains(target)) {
                return i;
            }
        }
        return -1;
    }

    private static int findXpBottleSlot(AbstractContainerScreen<?> screen) {
        int end = ExperimentSolver.containerSlotCount(screen);
        for (int i = 0; i < end; i++) {
            Slot slot = screen.getMenu().slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }
            String name = ExperimentSolver.slotName(screen, i).toLowerCase(Locale.ROOT);
            if (slot.getItem().is(Items.EXPERIENCE_BOTTLE)
                    || name.contains("bottle o' enchanting")
                    || name.contains("experience bottle")) {
                return i;
            }
        }
        return -1;
    }

    private static int findBestXpPurchaseSlot(AbstractContainerScreen<?> screen) {
        for (String bottle : XP_BOTTLE_PREFERENCE) {
            int slot = findSlotByName(screen, bottle);
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }

    private static BlockPos findNearbyTable(Minecraft client) {
        BlockPos center = client.player.blockPosition();
        Vec3 eye = client.player.getEyePosition();
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-TABLE_SCAN_RADIUS, -TABLE_SCAN_RADIUS, -TABLE_SCAN_RADIUS),
                center.offset(TABLE_SCAN_RADIUS, TABLE_SCAN_RADIUS, TABLE_SCAN_RADIUS))) {
            if (!client.level.getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) {
                continue;
            }
            double distSq = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = pos.immutable();
            }
        }
        return nearest;
    }

    private static boolean isLookingAt(Minecraft client, BlockPos pos) {
        return client.hitResult instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(pos);
    }

    private static void waitForRotation() {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (RotationManager.isRotating() && System.currentTimeMillis() < deadline && running) {
            MacroWorkerThread.sleep(20);
        }
        MacroWorkerThread.sleep(60);
    }

    private static boolean waitForContainerScreen(long timeoutMs) {
        Minecraft client = Minecraft.getInstance();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && running) {
            if (client.screen instanceof AbstractContainerScreen<?>) {
                return true;
            }
            MacroWorkerThread.sleep(50);
        }
        return false;
    }

    private static boolean shouldAbort(Minecraft client) {
        return !running || client == null || client.player == null || client.level == null;
    }

    private static boolean isDebug() {
        return AetherConfig.AUTO_EXPERIMENTS_DEBUG.get();
    }

    private static boolean isStep() {
        return AetherConfig.AUTO_EXPERIMENTS_STEP.get();
    }

    private static void logTitleOnce(String title) {
        if (!title.equals(lastLoggedTitle)) {
            lastLoggedTitle = title;
            debugLog("Menu opened: '" + title + "'");
        }
    }

    private static void debugLog(String message) {
        if (message.equals(lastDebugLine)) {
            return;
        }
        lastDebugLine = message;
        if (isDebug()) {
            ClientUtils.sendMessage("§bExperiments: §7" + message, false);
        } else {
            ClientUtils.sendDebugMessage("Experiments: " + message);
        }
    }

    private static void markProgress() {
        lastProgressAtMs = System.currentTimeMillis();
    }

    private static void resetState() {
        opening = false;
        playing = false;
        pendingXpBuy = false;
        awaitingXpMenu = false;
        xpBottleBought = false;
        xpBuysThisRun = 0;
        nextActionAtMs = 0L;
        tablePos = null;
        activeExperiment = null;
        solver = null;
        pendingClick = null;
        completed.clear();
        lastDebugLine = null;
        lastLoggedTitle = null;
    }

    private record TierOption(int slot, String name, int rank, int requiredLevels, boolean onCooldown) {
    }

    private record PendingClick(int slot, String reason, Runnable onSent) {
    }
}
