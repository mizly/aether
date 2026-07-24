package dev.aether.modules.experimentation;

import java.util.HashSet;
import java.util.Set;

import dev.aether.config.AetherConfig;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.util.BazaarUtils;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.Vec3;

/**
 * Orchestrates a full hands-off Experimentation Table session: walk to the
 * table, open it, let the tick solvers play each experiment, claim, renew with
 * bits + XP up to the configured cap, then restore whatever was running before.
 */
public final class ExperimentationManager {

    private static final long NAV_TIMEOUT_MS = 90_000L;
    private static final long GAME_OPEN_TIMEOUT_MS = 4_000L;
    private static final long GAME_PLAY_TIMEOUT_MS = 180_000L;
    private static final long TABLE_OPEN_TIMEOUT_MS = 4_000L;
    private static final int SESSION_LOOP_CAP = 40;

    private static volatile boolean running = false;
    private static volatile boolean cancelRequested = false;
    private static final Set<String> dumpedMenus = java.util.Collections.synchronizedSet(new HashSet<>());

    // The server's daily renewal counter, parsed from the "bonus charge (N/3)"
    // chat line, so the cap holds even across sessions and relogs.
    private static volatile int dailyRenewalsUsed = -1;
    private static volatile long refusalAt = 0L;
    private static volatile String refusalReason = "";
    private static volatile boolean xpLowHit = false;
    private static final int XP_BOTTLE_SESSION_CAP = 2;

    private static final java.util.regex.Pattern XP_COST_PATTERN =
            java.util.regex.Pattern.compile("starting cost: (\\d+) xp levels");
    private static final java.util.regex.Pattern RENEWAL_COUNT_PATTERN =
            java.util.regex.Pattern.compile("bonus charge for the Experimentation Table! \\((\\d)/3\\)");

    // Addons must be played before Superpairs - the server enforces this order.
    private static final String[] GAME_KEYS = {"chronomatron", "ultrasequencer", "superpairs"};

    private ExperimentationManager() {
    }

    public static boolean isRunning() {
        return running;
    }

    /** Wired into AetherChatEvents; flips flags the worker session polls. */
    public static void onChatMessage(String plainText) {
        if (plainText == null) {
            return;
        }
        String msg = plainText.trim();
        // Never react to the mod's own chat output - our debug lines quote the
        // server refusals and would re-trigger the refusal detector forever.
        String lowerAll = msg.toLowerCase();
        if (lowerAll.contains("[debug]") || lowerAll.contains("[experiments]") || lowerAll.contains("aether >>")) {
            return;
        }
        java.util.regex.Matcher matcher = RENEWAL_COUNT_PATTERN.matcher(msg);
        if (matcher.find()) {
            dailyRenewalsUsed = Integer.parseInt(matcher.group(1));
            return;
        }
        String lower = msg.toLowerCase();
        if (lower.contains("enough") && (lower.contains("xp") || lower.contains("level"))) {
            xpLowHit = true;
            refusalReason = msg;
            refusalAt = System.currentTimeMillis();
            return;
        }
        if (lower.contains("only play practice mode") && AetherConfig.EXPERIMENTS_PRACTICE_MODE.get()) {
            // Expected in practice mode - the server is telling us to right-click,
            // which is exactly what we do. Not a reason to skip the game.
            return;
        }
        if (lower.contains("only play practice mode")
                || lower.contains("experiment is on cooldown")
                || lower.contains("play the add-on experiments before")) {
            refusalReason = msg;
            refusalAt = System.currentTimeMillis();
        }
    }

    public static void cancel() {
        cancelRequested = true;
    }

    /** UI capture: stand in front of the table looking at it, like rewarp points. */
    public static void saveTablePosition() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        AetherConfig.EXPERIMENTS_TABLE_X.set(client.player.getBlockX());
        AetherConfig.EXPERIMENTS_TABLE_Y.set(client.player.getBlockY());
        AetherConfig.EXPERIMENTS_TABLE_Z.set(client.player.getBlockZ());
        AetherConfig.EXPERIMENTS_TABLE_YAW.set((double) client.player.getYRot());
        AetherConfig.EXPERIMENTS_TABLE_PITCH.set((double) client.player.getXRot());
        AetherConfig.EXPERIMENTS_TABLE_SET.set(true);
        AetherConfig.save();
        ClientUtils.sendMessage(String.format(
                "§aExperimentation spot saved: (%d, %d, %d), yaw %.0f, pitch %.0f.",
                AetherConfig.EXPERIMENTS_TABLE_X.get(),
                AetherConfig.EXPERIMENTS_TABLE_Y.get(),
                AetherConfig.EXPERIMENTS_TABLE_Z.get(),
                AetherConfig.EXPERIMENTS_TABLE_YAW.get(),
                AetherConfig.EXPERIMENTS_TABLE_PITCH.get()), false);
    }

    public static void manualTrigger() {
        Minecraft client = Minecraft.getInstance();
        if (running) {
            ClientUtils.sendMessage("§eExperimentation session already running.", false);
            return;
        }
        if (!AetherConfig.AUTO_EXPERIMENTS.get()) {
            ClientUtils.sendMessage("§cEnable Auto Experiments first - the solvers are gated on it.", false);
            return;
        }
        running = true;
        cancelRequested = false;
        ExperimentUtils.setRewardTarget(0);
        MacroWorkerThread.getInstance().submit("Experimentation", () -> runSession(client));
    }

    private static void runSession(Minecraft client) {
        // The session is usually started from the mod GUI. Close it before
        // entering our state: the tick handler stops any running macro state
        // while a non-container screen is open, which would cancel this task.
        closeNonContainerScreen(client);
        MacroWorkerThread.sleepRandom(250, 100);

        dumpedMenus.clear();
        refusalAt = 0L;
        boolean wasFarming = MacroStateManager.getCurrentState() == MacroState.State.FARMING;
        MacroStateManager.setCurrentState(MacroState.State.EXPERIMENTING);
        if (wasFarming) {
            client.execute(() -> FarmingMacroManager.disable(client));
            MacroWorkerThread.sleepRandom(250, 100);
        }

        int gamesPlayed = 0;
        int renewalsUsed = 0;
        int xpBottlesUsed = 0;
        int gamesAtLastRenewal = -1;
        Set<String> exhausted = new HashSet<>();
        Set<String> completed = new HashSet<>();
        try {
            if (!ensureTableOpen(client)) {
                return;
            }

            for (int guard = 0; guard < SESSION_LOOP_CAP; guard++) {
                if (shouldStop(client)) {
                    return;
                }
                if (!isTableScreen(client)) {
                    if (isRewardsScreen(client)) {
                        claimRewardsScreen(client);
                    }
                    // A leftover stakes/other menu makes the reopen click useless.
                    if (client.screen instanceof AbstractContainerScreen<?> && !isGameScreen(client)) {
                        client.execute(ExperimentUtils::closeScreen);
                        MacroWorkerThread.sleepRandom(250, 100);
                    }
                    if (!ensureTableOpen(client)) {
                        return;
                    }
                }
                // Hypixel fills chest menus asynchronously; scanning too early
                // sees an empty container.
                waitForMenuItems(client);

                int playSlot = findPlayableExperiment(client, exhausted, completed);
                if (playSlot >= 0) {
                    xpLowHit = false;
                    if (playExperiment(client, playSlot, exhausted, completed)) {
                        gamesPlayed++;
                    } else if (xpLowHit && AetherConfig.EXPERIMENTS_AUTO_BUY_XP.get()
                            && xpBottlesUsed < XP_BOTTLE_SESSION_CAP) {
                        // The table refused for XP levels; buy + splash a bottle
                        // and retry the same game at full tier.
                        xpLowHit = false;
                        xpBottlesUsed++;
                        buyAndSplashXpBottle(client);
                    }
                    continue;
                }

                if (AetherConfig.EXPERIMENTS_PRACTICE_MODE.get()) {
                    // Practice needs no charges, so renewing would just burn bits.
                    ClientUtils.sendDebugMessage("[Experiments] practice mode: not renewing.");
                    break;
                }
                int cap = AetherConfig.EXPERIMENTS_RENEWALS_PER_DAY.get();
                int used = Math.max(renewalsUsed, Math.max(0, dailyRenewalsUsed));
                if (gamesPlayed == gamesAtLastRenewal) {
                    // The last renewal bought us nothing playable (usually not
                    // enough XP levels); stop instead of spending again.
                    ClientUtils.sendMessage("§eRenewal did not unlock anything playable - stopping.", false);
                    break;
                }
                if (used < cap && tryRenew(client)) {
                    gamesAtLastRenewal = gamesPlayed;
                    renewalsUsed = used + 1;
                    exhausted.clear();
                    completed.clear();
                    continue;
                }
                break;
            }

            ClientUtils.sendMessage(String.format(
                    "§aExperimentation session done: %d game(s) played, %d renewal(s) used.",
                    gamesPlayed, renewalsUsed), false);
        } finally {
            client.execute(() -> {
                if (client.screen instanceof AbstractContainerScreen<?>) {
                    ExperimentUtils.closeScreen();
                }
            });
            MacroWorkerThread.sleepRandom(300, 100);
            if (wasFarming && !cancelRequested
                    && MacroStateManager.getCurrentState() == MacroState.State.EXPERIMENTING) {
                MacroStateManager.setCurrentState(MacroState.State.FARMING);
                client.execute(() -> FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()));
            } else if (MacroStateManager.getCurrentState() == MacroState.State.EXPERIMENTING) {
                MacroStateManager.setCurrentState(MacroState.State.OFF);
            }
            running = false;
        }
    }

    // -- Navigation -----------------------------------------------------------

    private static boolean ensureTableOpen(Minecraft client) {
        if (isTableScreen(client)) {
            return true;
        }
        if (!AetherConfig.EXPERIMENTS_TABLE_SET.get()) {
            ClientUtils.sendMessage("§cNo table spot saved. Set it in the GUI or open the table yourself first.", false);
            return false;
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            if (shouldStop(client)) {
                return false;
            }
            closeNonContainerScreen(client);
            MacroWorkerThread.sleep(100);
            if (!navigateToStand(client)) {
                continue;
            }
            aimAtTable(client);
            client.execute(ClientUtils::performUseClick);
            if (waitFor(client, () -> isTableScreen(client), TABLE_OPEN_TIMEOUT_MS)) {
                return true;
            }
            ClientUtils.sendDebugMessage("[Experiments] Table didn't open (attempt " + attempt + "/3).");
        }
        ClientUtils.sendMessage("§cCould not open the Experimentation Table.", false);
        return false;
    }

    private static boolean navigateToStand(Minecraft client) {
        Vec3 stand = new Vec3(
                AetherConfig.EXPERIMENTS_TABLE_X.get() + 0.5,
                AetherConfig.EXPERIMENTS_TABLE_Y.get(),
                AetherConfig.EXPERIMENTS_TABLE_Z.get() + 0.5);
        Vec3 pos = playerPos(client);
        if (pos == null) {
            return false;
        }
        if (pos.distanceTo(stand) <= 1.0) {
            return true;
        }

        ClientUtils.sendDebugMessage("[Experiments] Walking to table spot " + stand + ".");
        // Exact block-centre goal with a tight tolerance: the default walk stops
        // anywhere in the block and can overshoot past the table.
        client.execute(() -> PathfindingManager.startConfiguredWalk(
                client, stand, null, null, true, 0.25, true, false));
        MacroWorkerThread.sleepRandom(200, 60);
        long deadline = System.currentTimeMillis() + NAV_TIMEOUT_MS;
        while (PathfindingManager.isNavigating()) {
            if (shouldStop(client) || System.currentTimeMillis() > deadline) {
                PathfindingManager.stop(false);
                return false;
            }
            MacroWorkerThread.sleep(100);
        }
        Vec3 after = playerPos(client);
        boolean arrived = after != null && after.distanceTo(stand) <= 1.5;
        if (!arrived) {
            ClientUtils.sendDebugMessage("[Experiments] Walk ended away from the table spot.");
        }
        return arrived;
    }

    private static void aimAtTable(Minecraft client) {
        // Restore the exact view captured when the spot was set; that look was
        // pointing at the table by construction.
        float yaw = AetherConfig.EXPERIMENTS_TABLE_YAW.get().floatValue();
        float pitch = AetherConfig.EXPERIMENTS_TABLE_PITCH.get().floatValue();
        client.execute(() -> RotationManager.rotateToYawPitch(
                client, yaw, pitch, AetherConfig.ROTATION_TIME.get(), true));
        long deadline = System.currentTimeMillis() + 3_000L;
        while (RotationManager.isRotating() && System.currentTimeMillis() < deadline) {
            MacroWorkerThread.sleep(25);
        }
        MacroWorkerThread.sleepRandom(120, 60);
    }

    // -- Table menu -----------------------------------------------------------

    private static boolean isTableScreen(Minecraft client) {
        return client.screen instanceof AbstractContainerScreen<?> screen
                && stripped(screen.getTitle().getString()).toLowerCase().contains("experimentation");
    }

    private static boolean isGameScreen(Minecraft client) {
        return client.screen instanceof AbstractContainerScreen<?> screen
                && (SuperpairsSolver.isGameScreen(screen)
                        || ChronomatronSolver.isGameScreen(screen)
                        || UltrasequencerSolver.isGameScreen(screen));
    }

    private static int findPlayableExperiment(Minecraft client, Set<String> exhausted, Set<String> completed) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || screen.getMenu() == null) {
            return -1;
        }
        // Key order matters: addons first, Superpairs last. Completed games are
        // never re-entered this session (until a renewal resets the charges).
        for (String key : GAME_KEYS) {
            if (exhausted.contains(key) || completed.contains(key)) {
                continue;
            }
            for (int i = 0; i < ExperimentUtils.containerSlotCount(screen); i++) {
                String name = ExperimentUtils.stackName(ExperimentUtils.stackAt(screen, i)).toLowerCase();
                if (name.contains(key)) {
                    return i;
                }
            }
        }
        dumpMenuOnce(screen);
        return -1;
    }

    /** One-shot-per-menu diagnostic: log the menu contents when nothing matched. */
    private static void dumpMenuOnce(AbstractContainerScreen<?> screen) {
        if (!dumpedMenus.add(stripped(screen.getTitle().getString()))) {
            return;
        }
        StringBuilder names = new StringBuilder();
        int containerSize = ExperimentUtils.containerSlotCount(screen);
        for (int i = 0; i < containerSize; i++) {
            String name = ExperimentUtils.stackName(ExperimentUtils.stackAt(screen, i));
            if (!name.isEmpty()) {
                names.append(i).append("='").append(name).append("' ");
            }
        }
        ClientUtils.sendDebugMessage("[Experiments] No playable experiment matched. Menu '"
                + stripped(screen.getTitle().getString()) + "': " + names);
    }

    private static boolean playExperiment(Minecraft client, int slotIndex, Set<String> exhausted, Set<String> completed) {
        AbstractContainerScreen<?> table = client.screen instanceof AbstractContainerScreen<?> s ? s : null;
        if (table == null) {
            return false;
        }
        String key = gameKeyOf(ExperimentUtils.stackName(ExperimentUtils.stackAt(table, slotIndex)));
        refusalAt = 0L;
        boolean practice = AetherConfig.EXPERIMENTS_PRACTICE_MODE.get();
        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true));
        // Practice runs are started with a right-click, on the table entry as
        // well as on a stakes tier.
        client.execute(() -> {
            if (client.screen == table) {
                if (practice) {
                    ExperimentUtils.clickSlotRight(table, slotIndex);
                } else {
                    ExperimentUtils.clickSlot(table, slotIndex);
                }
            }
        });
        ClientUtils.sendDebugMessage("[Experiments] opening " + key
                + (practice ? " in practice mode (right-click)" : ""));

        // The click can lead to: the game directly, the stakes (tier) menu, or a
        // server refusal in chat. Poll for whichever happens first.
        boolean stakesHandled = false;
        long deadline = System.currentTimeMillis() + GAME_OPEN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop(client)) {
                return false;
            }
            if (isGameScreen(client)) {
                break;
            }
            if (recentRefusal()) {
                if (xpLowHit) {
                    // Not the game's fault - don't write it off, the session
                    // loop buys XP and retries.
                    ClientUtils.sendDebugMessage("[Experiments] " + key + " blocked by XP level.");
                    closeStrayMenu(client);
                    return false;
                }
                markExhausted(client, exhausted, key, refusalReason);
                return false;
            }
            if (!stakesHandled && isStakesScreen(client, key)) {
                stakesHandled = true;
                if (!pickStakesTier(client, key, exhausted)) {
                    return false;
                }
                deadline = System.currentTimeMillis() + GAME_OPEN_TIMEOUT_MS;
            }
            MacroWorkerThread.sleep(100);
        }
        if (!isGameScreen(client)) {
            markExhausted(client, exhausted, key, "no game screen opened");
            return false;
        }

        // The tick solvers play the game; wait until it ends (screen closes or
        // returns to the table).
        long playDeadline = System.currentTimeMillis() + GAME_PLAY_TIMEOUT_MS;
        while (isGameScreen(client)) {
            if (shouldStop(client) || System.currentTimeMillis() > playDeadline) {
                client.execute(ExperimentUtils::closeScreen);
                return false;
            }
            MacroWorkerThread.sleep(200);
        }
        if (key != null && !key.equals("superpairs")) {
            // Completing an addon can unlock Superpairs; let it be retried.
            exhausted.remove("superpairs");
        }

        // The server pops an "Experiment Over" rewards screen after the game;
        // claim everything in it before heading back to the table.
        waitFor(client, () -> isRewardsScreen(client), 2_500L);
        if (isRewardsScreen(client)) {
            claimRewardsScreen(client);
        }
        MacroWorkerThread.sleepRandom(400, 150);

        // Claim the finished game's rewards (one more click on its menu item),
        // then remember it as done so the session never replays it.
        if (key != null) {
            claimExperiment(client, key);
            completed.add(key);
        }
        return true;
    }

    private static void claimExperiment(Minecraft client, String key) {
        if (!isTableScreen(client) && !ensureTableOpen(client)) {
            return;
        }
        waitForMenuItems(client);
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        for (int i = 0; i < ExperimentUtils.containerSlotCount(screen); i++) {
            String name = ExperimentUtils.stackName(ExperimentUtils.stackAt(screen, i)).toLowerCase();
            if (name.contains(key)) {
                final int claimIndex = i;
                MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));
                client.execute(() -> {
                    if (client.screen == screen) {
                        ExperimentUtils.clickSlot(screen, claimIndex);
                    }
                });
                MacroWorkerThread.sleepRandom(600, 200);
                closeStrayMenu(client);
                return;
            }
        }
    }

    private static boolean isRewardsScreen(Minecraft client) {
        return client.screen instanceof AbstractContainerScreen<?> screen
                && stripped(screen.getTitle().getString()).toLowerCase().contains("experiment over");
    }

    /** Click every reward item in the "Experiment Over" screen, then close it. */
    private static void claimRewardsScreen(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || screen.getMenu() == null) {
            return;
        }
        waitForMenuItems(client);
        dumpMenuOnce(screen);
        int max = ExperimentUtils.containerSlotCount(screen);
        for (int i = 0; i < max; i++) {
            if (client.screen != screen) {
                return;
            }
            ItemStack stack = ExperimentUtils.stackAt(screen, i);
            if (stack.isEmpty() || ExperimentUtils.itemIdContains(stack, "glass")
                    || ExperimentUtils.itemIdContains(stack, "barrier")) {
                continue;
            }
            String lowerName = ExperimentUtils.stackName(stack).toLowerCase();
            if (lowerName.isEmpty() || lowerName.contains("close") || lowerName.contains("go back")) {
                continue;
            }
            final int claimIndex = i;
            MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));
            client.execute(() -> {
                if (client.screen == screen) {
                    ExperimentUtils.clickSlot(screen, claimIndex);
                }
            });
        }
        MacroWorkerThread.sleepRandom(400, 150);
        if (isRewardsScreen(client)) {
            client.execute(ExperimentUtils::closeScreen);
            MacroWorkerThread.sleepRandom(250, 100);
        }
    }

    private static void closeStrayMenu(Minecraft client) {
        if (client.screen instanceof AbstractContainerScreen<?> && !isTableScreen(client) && !isGameScreen(client)) {
            client.execute(ExperimentUtils::closeScreen);
            MacroWorkerThread.sleepRandom(250, 100);
        }
    }

    // -- XP bottle ------------------------------------------------------------

    private static void buyAndSplashXpBottle(Minecraft client) {
        ClientUtils.sendMessage("§eXP level too low for the experiment - buying a Titanic Experience Bottle.", false);
        client.execute(ExperimentUtils::closeScreen);
        MacroWorkerThread.sleepRandom(300, 100);

        if (!BazaarUtils.executeBuy(client, "Titanic Experience Bottle", 1)) {
            ClientUtils.sendMessage("§cCould not buy the XP bottle from Bazaar.", false);
            return;
        }

        // The bazaar can leave its screen open and the bought item takes a
        // moment to land in the inventory.
        closeNonContainerScreen(client);
        client.execute(() -> {
            if (client.screen instanceof AbstractContainerScreen<?>) {
                ExperimentUtils.closeScreen();
            }
        });
        MacroWorkerThread.sleepRandom(300, 100);

        boolean held = false;
        long holdDeadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < holdDeadline) {
            if (holdItemByName(client, "titanic experience bottle")) {
                held = true;
                break;
            }
            MacroWorkerThread.sleep(250);
        }
        if (!held) {
            ClientUtils.sendMessage("§cBought the XP bottle but couldn't hold it.", false);
            return;
        }

        // Step back from the table so the throw can't hit it or its hitbox.
        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyDown, true));
        MacroWorkerThread.sleep(350);
        client.execute(() -> ClientUtils.setKeyMappingState(client.options.keyDown, false));
        MacroWorkerThread.sleepRandom(150, 60);

        // Look straight up so the splash lands on us, then throw.
        float yaw = client.player == null ? 0f : client.player.getYRot();
        client.execute(() -> RotationManager.rotateToYawPitch(
                client, yaw, -90f, AetherConfig.ROTATION_TIME.get(), true));
        long rotDeadline = System.currentTimeMillis() + 2_000L;
        while (RotationManager.isRotating() && System.currentTimeMillis() < rotDeadline) {
            MacroWorkerThread.sleep(25);
        }
        MacroWorkerThread.sleepRandom(150, 60);

        int before = client.player == null ? 0 : client.player.experienceLevel;
        client.execute(ClientUtils::performUseClick);
        MacroWorkerThread.sleep(900);
        int after = client.player == null ? 0 : client.player.experienceLevel;
        if (after <= before) {
            // The throw can get eaten during rotation settle; one retry.
            client.execute(ClientUtils::performUseClick);
            MacroWorkerThread.sleep(900);
            after = client.player == null ? 0 : client.player.experienceLevel;
        }
        ClientUtils.sendDebugMessage("[Experiments] XP bottle splashed: level " + before + " -> " + after + ".");
    }

    /** Select the named item, swapping it into the hotbar first if needed. */
    private static boolean holdItemByName(Minecraft client, String nameNeedle) {
        if (client.player == null) {
            return false;
        }
        for (int i = 0; i < 9; i++) {
            if (invNameContains(client, i, nameNeedle)) {
                selectHotbar(client, i);
                return true;
            }
        }

        int source = -1;
        for (int i = 9; i < 36; i++) {
            if (invNameContains(client, i, nameNeedle)) {
                source = i;
                break;
            }
        }
        if (source < 0) {
            return false;
        }
        int hotbar = 8;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) {
                hotbar = i;
                break;
            }
        }

        // The survival inventory menu maps main-inventory indices 9..35 to the
        // same slot ids, so a SWAP click with the hotbar button moves the item.
        final int sourceSlot = source;
        final int hotbarButton = hotbar;
        client.execute(() -> client.setScreen(new InventoryScreen(client.player)));
        MacroWorkerThread.sleep(250);
        client.execute(() -> {
            if (client.screen instanceof AbstractContainerScreen<?> invScreen) {
                ClientUtils.performSlotClick(invScreen, sourceSlot, hotbarButton, ContainerInput.SWAP);
            }
        });
        MacroWorkerThread.sleep(200);
        client.execute(() -> {
            if (client.screen instanceof AbstractContainerScreen<?>) {
                ExperimentUtils.closeScreen();
            }
        });
        MacroWorkerThread.sleep(150);
        if (!invNameContains(client, hotbarButton, nameNeedle)) {
            return false;
        }
        selectHotbar(client, hotbarButton);
        return true;
    }

    private static void selectHotbar(Minecraft client, int slot) {
        client.execute(() -> FailsafeManager.selectHotbarSlot(client, slot));
        MacroWorkerThread.sleep(150);
    }

    private static boolean invNameContains(Minecraft client, int invIndex, String needle) {
        if (client.player == null) {
            return false;
        }
        ItemStack stack = client.player.getInventory().getItem(invIndex);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return TablistUtils.stripColors(stack.getHoverName().getString()).toLowerCase().contains(needle);
    }

    private static boolean pickStakesTier(Minecraft client, String key, Set<String> exhausted) {
        if (!(client.screen instanceof AbstractContainerScreen<?> stakes) || stakes.getMenu() == null) {
            return false;
        }
        waitForMenuItems(client);

        // Tier buttons are named like "Supreme Experiment". Lore can't reliably
        // tell locked/cooldown tiers apart, so collect them in menu order
        // (ascending difficulty) and try from the highest down until one opens,
        // using the server's refusal chat as the step-down signal.
        java.util.List<Integer> tiers = new java.util.ArrayList<>();
        int max = ExperimentUtils.containerSlotCount(stakes);
        for (int i = 0; i < max; i++) {
            ItemStack stack = ExperimentUtils.stackAt(stakes, i);
            if (stack.isEmpty() || ExperimentUtils.itemIdContains(stack, "glass")) {
                continue;
            }
            String lowerName = ExperimentUtils.stackName(stack).toLowerCase();
            if (lowerName.isEmpty() || lowerName.contains("close")
                    || lowerName.contains("go back") || lowerName.contains("practice")) {
                continue;
            }
            // The lore states lock status outright: "Enchanting level too low!"
            // vs "Click to play!"; don't waste clicks on locked tiers.
            String lore = fullLoreOf(client, stack).toLowerCase();
            if (lore.contains("too low")) {
                continue;
            }
            tiers.add(i);
        }
        if (tiers.isEmpty()) {
            dumpMenuOnce(stakes);
            markExhausted(client, exhausted, key, "no tier buttons found");
            client.execute(ExperimentUtils::closeScreen);
            MacroWorkerThread.sleepRandom(250, 100);
            return false;
        }

        for (int c = tiers.size() - 1; c >= 0; c--) {
            if (shouldStop(client)) {
                return false;
            }
            // Hypixel can replace the screen instance between attempts; always
            // click on the currently-open stakes screen.
            if (!isStakesScreen(client, key)
                    || !(client.screen instanceof AbstractContainerScreen<?> current)) {
                break;
            }
            int tierIndex = tiers.get(c);
            ItemStack tierStack = ExperimentUtils.stackAt(current, tierIndex);
            String tierLore = fullLoreOf(client, tierStack);
            ClientUtils.sendDebugMessage("[Experiments] Trying tier '"
                    + ExperimentUtils.stackName(tierStack) + "' for " + key
                    + ". Lore: " + tierLore);

            // Reward thresholds live in the lore; the solvers stop there.
            int rewardTarget = ExperimentUtils.parseRewardTarget(tierLore);
            ExperimentUtils.setRewardTarget(rewardTarget);
            boolean practice = AetherConfig.EXPERIMENTS_PRACTICE_MODE.get();
            if (rewardTarget > 0) {
                ClientUtils.sendDebugMessage("[Experiments] Max reward at series/chain of "
                        + rewardTarget + (practice ? " (practice run)" : ""));
            }

            // Unaffordable tiers are silently ignored by the server - no chat,
            // no screen change - so check the lore cost against our XP directly.
            // Practice runs are free, so the cost never applies.
            java.util.regex.Matcher costMatcher = XP_COST_PATTERN.matcher(tierLore.toLowerCase());
            if (!practice && costMatcher.find()) {
                int required = Integer.parseInt(costMatcher.group(1));
                int have = client.player == null ? 0 : client.player.experienceLevel;
                if (have < required) {
                    ClientUtils.sendDebugMessage("[Experiments] Tier needs " + required
                            + " XP levels, we have " + have + " - buying XP instead of stepping down.");
                    xpLowHit = true;
                    closeStrayMenu(client);
                    return false;
                }
            }
            refusalAt = 0L;
            MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));
            client.execute(() -> {
                if (client.screen == current) {
                    if (AetherConfig.EXPERIMENTS_PRACTICE_MODE.get()) {
                        ExperimentUtils.clickSlotRight(current, tierIndex);
                    } else {
                        ExperimentUtils.clickSlot(current, tierIndex);
                    }
                }
            });

            long deadline = System.currentTimeMillis() + 2_500L;
            while (System.currentTimeMillis() < deadline) {
                if (isGameScreen(client)) {
                    return true;
                }
                if (recentRefusal()) {
                    break;
                }
                MacroWorkerThread.sleep(100);
            }
            if (isGameScreen(client)) {
                return true;
            }
            if (xpLowHit) {
                // XP shortage applies to every tier below too; bail so the
                // session buys a bottle instead of stepping down.
                closeStrayMenu(client);
                return false;
            }
        }

        markExhausted(client, exhausted, key, "no tier would start (cooldown/charges?)");
        client.execute(ExperimentUtils::closeScreen);
        MacroWorkerThread.sleepRandom(250, 100);
        return false;
    }

    private static boolean isStakesScreen(Minecraft client, String key) {
        if (key == null || !(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }
        String title = stripped(screen.getTitle().getString()).trim().toLowerCase();
        return title.contains(key) && !title.contains("(");
    }

    private static boolean recentRefusal() {
        return refusalAt != 0L && System.currentTimeMillis() - refusalAt < 4_000L;
    }

    private static void markExhausted(Minecraft client, Set<String> exhausted, String key, String reason) {
        if (key != null) {
            exhausted.add(key);
            ClientUtils.sendDebugMessage("[Experiments] " + key + " not playable (" + reason + "), skipping.");
        }
        // A stakes menu may be left open after a refusal; clear it.
        if (client.screen instanceof AbstractContainerScreen<?> && !isTableScreen(client) && !isGameScreen(client)) {
            client.execute(ExperimentUtils::closeScreen);
            MacroWorkerThread.sleepRandom(250, 100);
        }
    }

    private static void waitForMenuItems(Minecraft client) {
        long deadline = System.currentTimeMillis() + 2_500L;
        while (System.currentTimeMillis() < deadline) {
            if (!(client.screen instanceof AbstractContainerScreen<?> screen) || screen.getMenu() == null) {
                return;
            }
            int named = 0;
            int max = ExperimentUtils.containerSlotCount(screen);
            for (int i = 0; i < max; i++) {
                if (!ExperimentUtils.stackName(ExperimentUtils.stackAt(screen, i)).isEmpty()) {
                    named++;
                }
            }
            if (named >= 3) {
                return;
            }
            MacroWorkerThread.sleep(100);
        }
    }

    private static String fullLoreOf(Minecraft client, ItemStack stack) {
        if (stack == null || stack.isEmpty() || client.player == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Component line : stack.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL)) {
            // The lock/cost lines sit at the END of long lore; a small cap hides
            // exactly the lines we filter on.
            if (text.length() > 1500) {
                break;
            }
            text.append(stripped(line.getString()).trim()).append(" | ");
        }
        return text.toString();
    }

    private static String gameKeyOf(String displayName) {
        String lower = displayName.toLowerCase();
        for (String key : GAME_KEYS) {
            if (lower.contains(key)) {
                return key;
            }
        }
        return null;
    }

    // -- Renewal --------------------------------------------------------------

    private static boolean tryRenew(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || screen.getMenu() == null) {
            return false;
        }
        int renewSlot = -1;
        for (int i = 0; i < ExperimentUtils.containerSlotCount(screen); i++) {
            String name = ExperimentUtils.stackName(ExperimentUtils.stackAt(screen, i)).toLowerCase();
            if (name.contains("renew")) {
                renewSlot = i;
                break;
            }
        }
        if (renewSlot < 0) {
            return false;
        }

        String cost = loreOf(client, ExperimentUtils.stackAt(screen, renewSlot));
        ClientUtils.sendDebugMessage("[Experiments] Renewing experiments. " + cost);
        final int clickIndex = renewSlot;
        MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(true));
        client.execute(() -> {
            if (client.screen == screen) {
                ExperimentUtils.clickSlot(screen, clickIndex);
            }
        });
        MacroWorkerThread.sleep(1_000L);

        // Some renew flows show a confirm chest; click the green confirm if so.
        if (client.screen instanceof AbstractContainerScreen<?> confirm
                && stripped(confirm.getTitle().getString()).toLowerCase().contains("confirm")) {
            for (int i = 0; i < ExperimentUtils.containerSlotCount(confirm); i++) {
                ItemStack stack = ExperimentUtils.stackAt(confirm, i);
                String name = ExperimentUtils.stackName(stack).toLowerCase();
                if (name.contains("confirm")
                        || ExperimentUtils.itemIdContains(stack, "green", "terracotta")) {
                    final int confirmIndex = i;
                    MacroWorkerThread.sleep(ClientUtils.getGuiClickDelayMs(false));
                    client.execute(() -> {
                        if (client.screen == confirm) {
                            ExperimentUtils.clickSlot(confirm, confirmIndex);
                        }
                    });
                    break;
                }
            }
        }

        return waitFor(client, () -> isTableScreen(client), TABLE_OPEN_TIMEOUT_MS);
    }

    private static String loreOf(Minecraft client, ItemStack stack) {
        if (stack == null || stack.isEmpty() || client.player == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Component line : stack.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL)) {
            String plain = stripped(line.getString()).trim();
            if (plain.toLowerCase().contains("bits") || plain.toLowerCase().contains("levels")) {
                text.append(plain).append(" ");
            }
        }
        return text.toString().trim();
    }

    // -- Helpers --------------------------------------------------------------

    private static boolean shouldStop(Minecraft client) {
        return cancelRequested
                || MacroWorkerThread.shouldAbortTask(client, MacroState.State.EXPERIMENTING);
    }

    private static boolean waitFor(Minecraft client, java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (shouldStop(client)) {
                return false;
            }
            MacroWorkerThread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    private static Vec3 playerPos(Minecraft client) {
        return client.player == null ? null : client.player.position();
    }

    private static void closeNonContainerScreen(Minecraft client) {
        client.execute(() -> {
            if (client.screen != null && !(client.screen instanceof AbstractContainerScreen<?>)) {
                client.setScreen(null);
            }
        });
    }

    private static String stripped(String text) {
        return text == null ? "" : text.replaceAll("(?i)§.", "");
    }
}
