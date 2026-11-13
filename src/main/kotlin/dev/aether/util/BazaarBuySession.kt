package dev.aether.util

import java.util.Locale
import java.util.regex.Pattern

internal class BazaarBuySession(item: String, private val count: Int) {
    private val item: String = normalize(item)
    companion object {
        private const val CONFIRM_SLOT = 13
        private val RECEIPT = Pattern.compile("\\[Bazaar] Bought ([\\d,]+)x (.+?) for [\\d,.]+ coins!", Pattern.CASE_INSENSITIVE)
        private val BUY_ITEM = Pattern.compile("(?:buy )?([\\d,]+)x (.+)")
        private val AMOUNT = Pattern.compile("amount:\\s*([\\d,]+)x")

        @JvmStatic fun isPurchaseDialog(title: String): Boolean {
            val plain = normalize(title)
            return plain.contains("confirm instant buy") || plain.contains("bazaar alert") || plain.contains("bazaar warning") || plain.contains("confirm purchase")
        }

        @JvmStatic fun isConfirmation(name: String): Boolean {
            val plain = normalize(name)
            return plain == "confirm" || plain.startsWith("confirm ") || plain == "buy anyway" || plain.startsWith("buy anyway ") || plain == "proceed" || plain.startsWith("proceed ") || plain == "buy" || plain == "buy instantly"
        }

        private fun normalize(text: String): String = TablistUtils.stripColors(text).trim().lowercase(Locale.ROOT)
    }

    @Volatile private var completed = false
    private var clickedMenu = -1
    private var clickedSlot = -1
    private var pendingMenu = -1
    private var pendingSlot = -1
    private var readyAt: Long = 0
    private var retryAt: Long = 0
    private var alertMenu = -1
    private var alertTitle = ""
    @Volatile private var blocker: String? = null

    fun item(): String = item

    class MenuItem @JvmOverloads constructor(val slot: Int, val name: String, val barrier: Boolean, val lore: List<String> = emptyList()) {
        fun slot(): Int = slot
        fun name(): String = name
        fun barrier(): Boolean = barrier
        fun lore(): List<String> = lore
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MenuItem) return false
            return slot == other.slot && barrier == other.barrier && name == other.name && lore == other.lore
        }

        override fun hashCode(): Int = java.util.Objects.hash(slot, name, barrier, lore)

        override fun toString(): String = "MenuItem[slot=$slot, name=$name, barrier=$barrier, lore=$lore]"
    }

    fun onChat(text: String) {
        val receipt = RECEIPT.matcher(TablistUtils.stripColors(text))
        if (receipt.find() && normalize(receipt.group(2)) == item && receipt.group(1).replace(",", "") == count.toString()) completed = true
    }

    fun completed(): Boolean = completed
    fun blocker(): String? = blocker

    fun confirmationSlot(menu: Int, title: String, items: List<MenuItem>, now: Long, delay: Long): Int {
        val plainTitle = normalize(title)
        var dialog = isPurchaseDialog(title) || (alertMenu == menu && alertTitle == plainTitle)
        var blocked = false
        var confirmSlot = -1
        for (entry in items) {
            val name = normalize(entry.name)
            if (entry.barrier && (name.contains("warning") || name.contains("alert") || name.contains("wait") || name.contains("second") || dialog && entry.slot == CONFIRM_SLOT)) {
                blocked = true
                dialog = true
                alertTitle = plainTitle
            }
        }
        if (dialog) {
            for (entry in items) {
                if (entry.barrier) continue
                if (entry.slot == CONFIRM_SLOT && (isPurchaseItem(entry.name) || isCustomAmountPurchase(entry))) {
                    confirmSlot = entry.slot
                    break
                }
                if (isConfirmation(entry.name)) confirmSlot = entry.slot
            }
        }
        return if (shouldConfirm(menu, confirmSlot, blocked, now, delay)) confirmSlot else -1
    }

    private fun isPurchaseItem(name: String): Boolean {
        val plain = normalize(name)
        if (plain == item || plain == "buy $item") return true
        val label = BUY_ITEM.matcher(plain)
        return label.matches() && label.group(2) == item && label.group(1).replace(",", "") == count.toString()
    }

    private fun isCustomAmountPurchase(entry: MenuItem): Boolean {
        if (normalize(entry.name) != "custom amount") return false
        var matchingItem = false
        var matchingAmount = false
        var readyToBuy = false
        var refusal: String? = null
        for (line in entry.lore) {
            val plain = normalize(line)
            if (plain == item) matchingItem = true
            val amount = AMOUNT.matcher(plain)
            if (amount.matches() && amount.group(1).replace(",", "") == count.toString()) matchingAmount = true
            if (plain == "click to buy now!") readyToBuy = true
            else if (refusal == null && !isPriceLine(plain)) refusal = TablistUtils.stripColors(line).trim()
        }
        blocker = if (matchingItem && matchingAmount && !readyToBuy) refusal else null
        return matchingItem && matchingAmount && readyToBuy
    }

    private fun isPriceLine(plain: String): Boolean = plain.isEmpty() || plain == item || plain.startsWith("amount:") || plain.startsWith("per unit:") || plain.startsWith("price:") || plain.startsWith("npc sell price:") || plain.startsWith("bazaar buy price:") || plain.startsWith("bazaar sell price:")

    fun shouldConfirm(menu: Int, slot: Int, blocked: Boolean, now: Long, delay: Long): Boolean {
        if (completed) return false
        if (blocked || slot < 0) {
            pendingMenu = -1
            pendingSlot = -1
            if (blocked) {
                clickedMenu = -1
                clickedSlot = -1
                alertMenu = menu
            }
            return false
        }
        if (menu == clickedMenu && slot == clickedSlot) {
            if (now < retryAt) return false
            retryAt = now + Math.max(500L, delay)
            return true
        }
        if (menu != pendingMenu || slot != pendingSlot) {
            pendingMenu = menu
            pendingSlot = slot
            readyAt = now + delay
        }
        if (now < readyAt) return false
        clickedMenu = menu
        clickedSlot = slot
        retryAt = now + Math.max(500L, delay)
        return true
    }
}
