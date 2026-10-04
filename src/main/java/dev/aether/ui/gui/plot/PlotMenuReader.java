package dev.aether.ui.gui.plot;

import dev.aether.ui.settings.PlotToken;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// reads hypixel's Configure Plots chest while the player has it open and never clicks anything, so a macro's
// inventory failsafes see nothing unusual. hypixel names the profile in chat when you join skyblock
public final class PlotMenuReader {
    public static final String TITLE = "Configure Plots";
    private static final Pattern PROFILE_ID = Pattern.compile("^Profile ID: ([0-9a-fA-F-]{32,36})");
    // a few reads a second is plenty, the menu fills in once after opening
    private static final int READ_EVERY_TICKS = 4;

    private static volatile String profileId;
    private static PlotMenuStore store;

    private PlotMenuReader() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onChat(message.getString());
            }
        });
    }

    public static synchronized PlotMenuStore store() {
        if (store == null) {
            store = PlotMenuStore.config();
        }
        return store;
    }

    public static String profileId() {
        return profileId;
    }

    public static PlotMenuSnapshot current() {
        return store().current(profileId);
    }

    static void onChat(String text) {
        Matcher matcher = PROFILE_ID.matcher(PlotInfo.strip(text));
        if (matcher.find()) {
            profileId = matcher.group(1).toLowerCase(Locale.ROOT);
        }
    }

    // called for every screen that finished init; only the Configure Plots chest is watched
    public static void watch(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || !TITLE.equals(PlotInfo.strip(container.getTitle().getString()))) {
            return;
        }
        int[] ticks = {0};
        boolean[] changed = {false};
        ScreenEvents.afterTick(screen).register(ticked -> {
            if (ticks[0]++ % READ_EVERY_TICKS != 0) {
                return;
            }
            PlotMenuSnapshot snapshot = read(container.getMenu(), Instant.now().toEpochMilli());
            if (snapshot.complete()) {
                changed[0] |= store().put(profileId, snapshot);
            }
        });
        ScreenEvents.remove(screen).register(removed -> {
            if (changed[0]) {
                store().save();
            }
        });
    }

    static PlotMenuSnapshot read(AbstractContainerMenu menu, long now) {
        Map<Integer, PlotMenuSnapshot.Slot> items = new HashMap<>();
        for (int plot = PlotToken.BARN; plot <= PlotToken.MAX_PLOT; plot++) {
            int slotIndex = PlotSlots.slotOf(plot);
            if (slotIndex >= menu.slots.size()) {
                continue;
            }
            Slot slot = menu.getSlot(slotIndex);
            if (slot != null && slot.hasItem()) {
                items.put(plot, item(slot.getItem()));
            }
        }
        return new PlotMenuSnapshot(items, now);
    }

    static PlotMenuSnapshot.Slot item(ItemStack stack) {
        List<String> lore = new ArrayList<>();
        ItemLore itemLore = stack.get(DataComponents.LORE);
        if (itemLore != null) {
            itemLore.lines().forEach(line -> lore.add(legacy(line)));
        }
        return new PlotMenuSnapshot.Slot(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), legacy(stack.getHoverName()), lore);
    }

    // back to § codes, the way hypixel wrote them, so the picker can draw names and lore in their colours
    static String legacy(Component component) {
        StringBuilder out = new StringBuilder();
        component.visit((style, part) -> {
            if (!part.isEmpty()) {
                out.append(codes(style)).append(part);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    private static String codes(Style style) {
        StringBuilder codes = new StringBuilder();
        TextColor color = style.getColor();
        ChatFormatting formatting = color == null ? null : nearest(color);
        codes.append('§').append(formatting == null ? 'r' : formatting.getChar());
        if (style.isBold()) {
            codes.append("§l");
        }
        if (style.isObfuscated()) {
            codes.append("§k");
        }
        return codes.toString();
    }

    private static ChatFormatting nearest(TextColor color) {
        ChatFormatting named = ChatFormatting.getByName(color.serialize());
        if (named != null && named.isColor()) {
            return named;
        }
        int rgb = color.getValue();
        ChatFormatting best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ChatFormatting formatting : ChatFormatting.values()) {
            Integer value = formatting.getColor();
            if (!formatting.isColor() || value == null) {
                continue;
            }
            int dr = (value >> 16 & 255) - (rgb >> 16 & 255);
            int dg = (value >> 8 & 255) - (rgb >> 8 & 255);
            int db = (value & 255) - (rgb & 255);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = formatting;
            }
        }
        return best;
    }
}
