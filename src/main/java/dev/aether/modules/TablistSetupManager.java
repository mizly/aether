package dev.aether.modules;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TablistSetupManager {
    private static final Set<String> ENABLED_WIDGETS = Set.of(
            "General Info Widget",
            "Profile Widget",
            "Stats Widget",
            "Skills Widget",
            "Jacob's Contest Widget",
            "Pests Widget",
            "Visitors Widget",
            "Composter Widget",
            "Pest Traps Widget",
            "Pet Widget");
    private static final Pattern PAGE_PATTERN = Pattern.compile("\\((\\d+)/(\\d+)\\)");
    private static final long AUTO_START_DELAY_MS = 3_000L;
    private static final long TIMEOUT_MS = 60_000L;

    private static final Set<Integer> completedPages = new HashSet<>();
    private static boolean active;
    private static boolean visitorsConfigured;
    private static boolean pestsConfigured;
    private static int visitorAmountClicks;
    private static boolean automaticAttempted;
    private static long automaticStartAt;
    private static long deadline;
    private static long nextActionAt;

    private TablistSetupManager() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void stop() {
        if (active) {
            active = false;
            automaticAttempted = true;
        }
    }

    public static int start(Minecraft client) {
        if (active) {
            ClientUtils.sendMessage("\u00A7eTablist setup is already running.", false);
            return 0;
        }
        if (client == null || client.player == null || client.getConnection() == null) {
            return 0;
        }
        if (MacroStateManager.isMacroRunning()) {
            ClientUtils.sendMessage("\u00A7cStop the active macro before changing tablist widgets.", false);
            return 0;
        }

        active = true;
        visitorsConfigured = false;
        pestsConfigured = false;
        visitorAmountClicks = 0;
        completedPages.clear();
        deadline = System.currentTimeMillis() + TIMEOUT_MS;
        nextActionAt = 0L;
        ClientUtils.sendMessage("\u00A7eApplying Aether's Garden tablist setup...", false);
        ClientUtils.sendCommand("/widgets");
        return 1;
    }

    public static void update(Minecraft client) {
        long now = System.currentTimeMillis();
        if (!active) {
            updateAutomaticStart(client, now);
            return;
        }

        if (client.player == null || client.getConnection() == null || now >= deadline) {
            finish(client, false, "Timed out waiting for the Tablist Widgets menu.");
            return;
        }
        if (MacroStateManager.isMacroRunning()) {
            finish(client, false, "Tablist setup stopped because a macro started.");
            return;
        }
        if (now < nextActionAt || !(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }

        String title = clean(screen.getTitle().getString());
        if (title.equalsIgnoreCase("Tablist Widgets")) {
            clickNamed(screen, "Widgets In Garden", 0);
        } else if (title.contains("Widgets in Garden")) {
            configureWidgetPage(screen, title);
        } else if (title.equals("Visitors Widget Settings")) {
            configureSettings(screen, false);
        } else if (title.equals("Pests Widget Settings")) {
            configureSettings(screen, true);
        }
    }

    private static void updateAutomaticStart(Minecraft client, long now) {
        MacroState.Location location = ClientUtils.getCurrentLocation();
        if (!needsAutomaticSetup(AetherConfig.TABLIST_SETUP_COMPLETE.get(), location)) {
            automaticAttempted = false;
            automaticStartAt = 0L;
            return;
        }
        if (automaticAttempted || MacroStateManager.isMacroRunning()) {
            automaticStartAt = 0L;
            return;
        }
        if (automaticStartAt == 0L) {
            automaticStartAt = now + AUTO_START_DELAY_MS;
            client.gui.setTimes(10, 60, 10);
            client.gui.setTitle(Component.literal("running necessary setup")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            client.gui.setSubtitle(Component.literal("please wait")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            return;
        }
        if (now < automaticStartAt) {
            return;
        }

        automaticAttempted = true;
        automaticStartAt = 0L;
        start(client);
    }

    static boolean needsAutomaticSetup(boolean completed, MacroState.Location location) {
        return !completed && location == MacroState.Location.GARDEN;
    }

    private static void configureWidgetPage(AbstractContainerScreen<?> screen, String title) {
        for (Slot slot : menuSlots(screen)) {
            if (!slot.hasItem()) {
                continue;
            }

            String name = clean(slot.getItem().getHoverName().getString());
            String lore = lore(slot.getItem());
            if (name.contains("Third Column") && Boolean.FALSE.equals(toggleState(lore))) {
                click(screen, slot.index, 0);
                return;
            }
            if (!name.endsWith(" Widget") || !lore.contains("Currently:")) {
                continue;
            }

            boolean enabled = lore.contains("Currently: ENABLED") || lore.contains("Currently: ALWAYS ENABLED");
            if (enabled != shouldEnableWidget(name)) {
                click(screen, slot.index, 0);
                return;
            }
        }

        Matcher page = PAGE_PATTERN.matcher(title);
        boolean hasPage = page.find();
        int currentPage = hasPage ? Integer.parseInt(page.group(1)) : 1;
        int pageCount = hasPage ? Integer.parseInt(page.group(2)) : 1;
        completedPages.add(currentPage);

        if (currentPage == 1 && !visitorsConfigured && rightClickWidget(screen, "Visitors Widget")) {
            return;
        }
        if (currentPage == 1 && !pestsConfigured && rightClickWidget(screen, "Pests Widget")) {
            return;
        }

        for (int targetPage = 1; targetPage <= pageCount; targetPage++) {
            if (!completedPages.contains(targetPage)) {
                clickNamed(screen, targetPage > currentPage ? "Next Page" : "Previous Page", 0);
                return;
            }
        }

        finish(Minecraft.getInstance(), true, "Garden tablist widgets configured.");
    }

    private static void configureSettings(AbstractContainerScreen<?> screen, boolean pests) {
        for (Slot slot : menuSlots(screen)) {
            if (!slot.hasItem()) {
                continue;
            }

            String name = clean(slot.getItem().getHoverName().getString());
            String lore = lore(slot.getItem());
            if (!pests && name.equals("Visitor Amount") && !isFiveVisitorsSelected(lore)) {
                if (++visitorAmountClicks > 6) {
                    finish(Minecraft.getInstance(), false, "Could not select 5 visitors.");
                    return;
                }
                click(screen, slot.index, 0);
                return;
            }

            Boolean enabled = toggleState(lore);
            if (enabled != null && enabled != shouldEnableSetting(name, pests)) {
                click(screen, slot.index, 0);
                return;
            }
        }

        if (pests) {
            pestsConfigured = true;
        } else {
            visitorsConfigured = true;
        }
        clickNamed(screen, "Go Back", 0);
    }

    static boolean shouldEnableWidget(String name) {
        return ENABLED_WIDGETS.contains(withoutStatus(name));
    }

    static boolean isFiveVisitorsSelected(String lore) {
        return lore.lines().anyMatch(line ->
                line.endsWith("5 Visitors") && !line.startsWith("5 Visitors"));
    }

    static boolean shouldEnableSetting(String name, boolean pests) {
        name = withoutStatus(name);
        return pests || name.equals("Spacing") || name.equals("Wrapping");
    }

    private static String withoutStatus(String name) {
        return name.replaceFirst("^[✔✖]\\s*", "");
    }

    private static Boolean toggleState(String lore) {
        if (lore.contains("Click to disable!")) {
            return true;
        }
        if (lore.contains("Click to enable!")) {
            return false;
        }
        return null;
    }

    private static boolean rightClickWidget(AbstractContainerScreen<?> screen, String widgetName) {
        return clickNamed(screen, widgetName, 1);
    }

    private static boolean clickNamed(AbstractContainerScreen<?> screen, String target, int mouseButton) {
        for (Slot slot : menuSlots(screen)) {
            if (slot.hasItem() && clean(slot.getItem().getHoverName().getString()).contains(target)) {
                click(screen, slot.index, mouseButton);
                return true;
            }
        }
        return false;
    }

    private static void click(AbstractContainerScreen<?> screen, int slot, int mouseButton) {
        ClientUtils.performSlotClick(screen, slot, mouseButton, ContainerInput.PICKUP);
        nextActionAt = System.currentTimeMillis() + Math.max(150L, ClientUtils.getGuiClickDelayMs(false));
    }

    private static List<Slot> menuSlots(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        return slots.subList(0, Math.max(0, slots.size() - 36));
    }

    private static String lore(ItemStack stack) {
        StringBuilder result = new StringBuilder();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return "";
        }
        List<Component> lines = stack.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL);
        for (Component line : lines) {
            result.append(clean(line.getString())).append('\n');
        }
        return result.toString();
    }

    private static String clean(String text) {
        return TablistUtils.stripColors(text).trim();
    }

    private static void finish(Minecraft client, boolean success, String message) {
        active = false;
        if (success) {
            AetherConfig.TABLIST_SETUP_COMPLETE.set(true);
            AetherConfig.save();
        }
        if (client != null && client.player != null) {
            client.player.closeContainer();
        }
        ClientUtils.sendMessage((success ? "\u00A7a" : "\u00A7c") + message, false);
    }
}
