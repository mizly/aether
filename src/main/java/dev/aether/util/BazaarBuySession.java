package dev.aether.util;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class BazaarBuySession {
    private static final int CONFIRM_SLOT = 13;
    private static final Pattern RECEIPT = Pattern.compile(
            "\\[Bazaar] Bought ([\\d,]+)x (.+?) for [\\d,.]+ coins!", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUY_ITEM = Pattern.compile("(?:buy )?([\\d,]+)x (.+)");
    private static final Pattern AMOUNT = Pattern.compile("amount:\\s*([\\d,]+)x");

    private final String item;
    private final int count;
    private volatile boolean completed;
    private int clickedMenu = -1;
    private int clickedSlot = -1;
    private int pendingMenu = -1;
    private int pendingSlot = -1;
    private long readyAt;
    private int alertMenu = -1;
    private String alertTitle = "";

    record MenuItem(int slot, String name, boolean barrier, List<String> lore) {
        MenuItem(int slot, String name, boolean barrier) {
            this(slot, name, barrier, List.of());
        }
    }

    BazaarBuySession(String item, int count) {
        this.item = normalize(item);
        this.count = count;
    }

    void onChat(String text) {
        var receipt = RECEIPT.matcher(TablistUtils.stripColors(text));
        if (receipt.find() && normalize(receipt.group(2)).equals(item)
                && receipt.group(1).replace(",", "").equals(Integer.toString(count))) {
            completed = true;
        }
    }

    boolean completed() { return completed; }

    int confirmationSlot(int menu, String title, List<MenuItem> items, long now, long delay) {
        String plainTitle = normalize(title);
        boolean dialog = isPurchaseDialog(title) || (alertMenu == menu && alertTitle.equals(plainTitle));
        boolean blocked = false;
        int confirmSlot = -1;
        for (MenuItem entry : items) {
            String name = normalize(entry.name());
            if (entry.barrier() && (name.contains("warning") || name.contains("alert")
                    || name.contains("wait") || name.contains("second")
                    || dialog && entry.slot() == CONFIRM_SLOT)) {
                blocked = true;
                dialog = true;
                alertTitle = plainTitle;
            }
        }
        if (dialog) {
            for (MenuItem entry : items) {
                if (entry.barrier()) continue;
                // Custom Amount identifies the purchase in its lore instead of its button name.
                if (entry.slot() == CONFIRM_SLOT
                        && (isPurchaseItem(entry.name()) || isCustomAmountPurchase(entry))) {
                    confirmSlot = entry.slot();
                    break;
                }
                if (isConfirmation(entry.name())) confirmSlot = entry.slot();
            }
        }
        return shouldConfirm(menu, confirmSlot, blocked, now, delay) ? confirmSlot : -1;
    }

    private boolean isPurchaseItem(String name) {
        String plain = normalize(name);
        if (plain.equals(item) || plain.equals("buy " + item)) return true;
        var label = BUY_ITEM.matcher(plain);
        return label.matches() && label.group(2).equals(item)
                && label.group(1).replace(",", "").equals(Integer.toString(count));
    }

    private boolean isCustomAmountPurchase(MenuItem entry) {
        if (!normalize(entry.name()).equals("custom amount")) return false;
        boolean matchingItem = false;
        boolean matchingAmount = false;
        boolean readyToBuy = false;
        for (String line : entry.lore()) {
            String plain = normalize(line);
            if (plain.equals(item)) matchingItem = true;
            var amount = AMOUNT.matcher(plain);
            if (amount.matches() && amount.group(1).replace(",", "").equals(Integer.toString(count))) {
                matchingAmount = true;
            }
            if (plain.equals("click to buy now!")) readyToBuy = true;
        }
        return matchingItem && matchingAmount && readyToBuy;
    }

    boolean shouldConfirm(int menu, int slot, boolean blocked, long now, long delay) {
        if (completed) return false;
        if (blocked || slot < 0) {
            pendingMenu = pendingSlot = -1;
            // A confirmation can open an alert in the same container and slot.
            if (blocked) {
                clickedMenu = clickedSlot = -1;
                alertMenu = menu;
            }
            return false;
        }
        if (menu == clickedMenu && slot == clickedSlot) return false;
        if (menu != pendingMenu || slot != pendingSlot) {
            pendingMenu = menu;
            pendingSlot = slot;
            readyAt = now + delay;
        }
        if (now < readyAt) return false;
        clickedMenu = menu;
        clickedSlot = slot;
        return true;
    }

    static boolean isPurchaseDialog(String title) {
        String plain = normalize(title);
        return plain.contains("confirm instant buy") || plain.contains("bazaar alert")
                || plain.contains("bazaar warning") || plain.contains("confirm purchase");
    }

    static boolean isConfirmation(String name) {
        String plain = normalize(name);
        return plain.equals("confirm") || plain.startsWith("confirm ") || plain.equals("buy anyway")
                || plain.startsWith("buy anyway ") || plain.equals("proceed") || plain.startsWith("proceed ")
                || plain.equals("buy") || plain.equals("buy instantly");
    }

    private static String normalize(String text) {
        return TablistUtils.stripColors(text).trim().toLowerCase(Locale.ROOT);
    }
}
