package dev.aether.modules.profit

import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.profit.helpers.FarmingXpTracker
import dev.aether.util.TablistUtils
import net.minecraft.network.chat.Component
import java.util.LinkedHashMap
import java.util.regex.Pattern

object ProfitManager {
    private val COUNT_SUFFIX = Pattern.compile("\\s+[xX](\\d+)$")
    private const val SAVE_INTERVAL_MS = 5000L
    private val SESSION_HISTORY = SessionProfitHistory()
    private val sessionCounts = LinkedHashMap<String, Long>()
    private val dailyCounts = LinkedHashMap<String, Long>()
    private val lifetimeCounts = LinkedHashMap<String, Long>()
    private val PERSISTENCE = ProfitPersistence()
    private val PRICING = ProfitPricing(::invalidateDisplayCaches)
    // assigned in the init block below: ProfitPricing's constructor pushes a change notification back
    // through invalidateDisplayCaches before this service exists, exactly like the Java field order
    private var DISPLAYS: ProfitDisplayService? = null

    init {
        DISPLAYS = ProfitDisplayService(java.util.function.Function<String, Map<String, Long>> { mode: String -> resolveCounts(mode) as Map<String, Long> }, PRICING)
    }
    private val CHAT_PARSER = ProfitChatParser()
    private val LIVE_TRACKER = ProfitLiveTracker { PRICING.refreshBazaarPricesIfNeeded() }
    private val CHAT_EVENT_SINK = object : ProfitChatParser.ProfitEventSink {
        override fun addDrop(itemName: String, count: Long) = this@ProfitManager.addDrop(itemName, count)
        override fun addVisitorGain(itemName: String, count: Long) { ProfitManager.addVisitorGain(itemName, count) }
    }

    private var spraySessionQuantity = 0L
    private var sprayDailyQuantity = 0L
    private var sprayLifetimeQuantity = 0L
    private var lastDailyResetDate = PERSISTENCE.getCurrentDateString()
    private var saveDirty = false
    private var lastSaveMillis = 0L
    @JvmField @Volatile var isSprayPhaseActive = false

    @JvmStatic fun startSprayPhase() { isSprayPhaseActive = true }
    @JvmStatic fun stopSprayPhase() { isSprayPhaseActive = false }
    @JvmStatic fun handleChatMessage(component: Component?) {
        if (isProfitTrackingActive()) CHAT_PARSER.handleChatMessage(component, CHAT_EVENT_SINK)
    }

    @JvmStatic fun addVisitorGain(itemName: String, count: Long) {
        if (!isProfitTrackingActive()) return
        var cleanName = sanitizeTrackedName(itemName).replace("+", "").trim()
        if (cleanName.isEmpty()) return
        var multiplier = 1L
        val suffix = COUNT_SUFFIX.matcher(cleanName)
        if (suffix.find()) try {
            multiplier = suffix.group(1).toLong()
            cleanName = cleanName.substring(0, suffix.start()).trim()
        } catch (_: Exception) { }
        val canonical = PRICING.canonicalizeTrackedName(cleanName)
        val key = if (canonical.startsWith("[Visitor] ")) canonical else "[Visitor] $canonical"
        addToAllCounts(key, count * multiplier)
    }

    @JvmStatic fun addVisitorCost(coinsSpent: Long) {
        if (isProfitTrackingActive()) addToAllCounts("[Visitor] Visitor Cost", -coinsSpent)
    }

    @JvmStatic fun addSprayCost(quantity: Int, coins: Long) {
        if (!isProfitTrackingActive()) return
        checkDailyReset()
        spraySessionQuantity += quantity
        sprayDailyQuantity += quantity
        sprayLifetimeQuantity += quantity
        listOf(sessionCounts, dailyCounts, lifetimeCounts).forEach { it["[Spray] Sprayonator"] = it.getOrDefault("[Spray] Sprayonator", 0L) - coins }
        invalidateDisplayCaches()
        markDirty()
    }

    @JvmStatic fun getSprayQuantity(lifetime: Boolean) = if (lifetime) sprayLifetimeQuantity else spraySessionQuantity
    @JvmStatic fun getSprayQuantity(mode: String) = when (mode) { "daily" -> sprayDailyQuantity; "lifetime" -> sprayLifetimeQuantity; else -> spraySessionQuantity }
    @JvmStatic fun getCategorizedName(name: String) = PRICING.getCategorizedName(name)
    @JvmStatic fun getCompactCategoryLabel(category: String) = PRICING.getCompactCategoryLabel(category)
    @JvmStatic fun getActiveDrops(): Map<String, Long> = getActiveDrops("session")
    @JvmStatic fun getActiveDrops(lifetime: Boolean): Map<String, Long> = getActiveDrops(if (lifetime) "lifetime" else "session")
    @JvmStatic fun getActiveDrops(mode: String) = DISPLAYS!!.getActiveDrops(mode)
    @JvmStatic fun getCompactDrops(): Map<String, Long> = getCompactDrops("session")
    @JvmStatic fun getCompactDrops(lifetime: Boolean): Map<String, Long> = getCompactDrops(if (lifetime) "lifetime" else "session")
    @JvmStatic fun getCompactDrops(mode: String) = DISPLAYS!!.getCompactDrops(mode)

    @JvmStatic fun reset() {
        sessionCounts.clear()
        SESSION_HISTORY.reset(System.nanoTime(), isProfitTrackingActive())
        spraySessionQuantity = 0
        CHAT_PARSER.resetSessionState()
        LIVE_TRACKER.resetSessionState()
        FarmingXpTracker.reset()
        invalidateDisplayCaches()
    }

    @JvmStatic fun resetDaily() {
        dailyCounts.clear()
        sprayDailyQuantity = 0
        lastDailyResetDate = PERSISTENCE.getCurrentDateString()
        invalidateDisplayCaches()
        saveDaily()
    }
    @JvmStatic fun resetLifetime() { lifetimeCounts.clear(); invalidateDisplayCaches(); saveLifetime() }
    @JvmStatic fun getTotalProfit(): Long = getTotalProfit("session")
    @JvmStatic fun getTotalProfit(lifetime: Boolean): Long = getTotalProfit(if (lifetime) "lifetime" else "session")
    @JvmStatic fun getTotalProfit(mode: String) = DISPLAYS!!.getTotalProfit(mode)
    @JvmStatic fun loadLifetime() = PERSISTENCE.loadLifetime(lifetimeCounts, Runnable { invalidateDisplayCaches() })

    @JvmStatic fun loadDaily() {
        val snapshot = PERSISTENCE.loadDaily() ?: return
        lastDailyResetDate = snapshot.resetDate
        if (lastDailyResetDate != PERSISTENCE.getCurrentDateString()) { resetDaily(); return }
        dailyCounts.clear(); dailyCounts.putAll(snapshot.counts)
        sprayDailyQuantity = snapshot.sprayQuantity
        invalidateDisplayCaches()
    }

    @JvmStatic fun getItemPrice(itemName: String) = PRICING.getItemPrice(itemName)
    @JvmStatic fun getNpcPrice(itemName: String) = PRICING.getNpcPrice(itemName)
    @JvmStatic fun getItemValue(itemName: String, count: Long) = PRICING.getItemValue(itemName, count)
    @JvmStatic fun isPredefinedTrackedItem(itemName: String) = PRICING.isPredefinedTrackedItem(itemName)

    @JvmStatic fun update() {
        LIVE_TRACKER.update(net.minecraft.client.Minecraft.getInstance()) { name, count -> addDrop(name, count) }
        SESSION_HISTORY.record(System.nanoTime(), getTotalProfit())
        if (saveDirty && System.currentTimeMillis() - lastSaveMillis >= SAVE_INTERVAL_MS) flush()
    }

    @JvmStatic fun flush() {
        if (!saveDirty) return
        saveDirty = false
        lastSaveMillis = System.currentTimeMillis()
        saveLifetime(); saveDaily()
    }
    private fun markDirty() { saveDirty = true }
    @JvmStatic fun updateSessionGraphClock() = SESSION_HISTORY.setRunning(System.nanoTime(), isProfitTrackingActive())
    @JvmStatic fun getSessionProfitHistory(nowNanos: Long) = SESSION_HISTORY.snapshot(nowNanos)
    @JvmStatic fun printPetXpPriceDebug() = PRICING.printPetXpPriceDebug(net.minecraft.client.Minecraft.getInstance())
    @JvmStatic fun addPetXp(petName: String, xpAmount: Long) { if (xpAmount > 0) addDrop("Pet XP ($petName)", xpAmount) }
    @JvmStatic fun reloadConfiguredPetXpPrices() = PRICING.reloadConfiguredPetXpPrices()
    @JvmStatic fun handlePriceSourceChanged() = PRICING.handlePriceSourceChanged()
    @JvmStatic fun handleShardPriceSourceChanged() = PRICING.handleShardPriceSourceChanged()
    @JvmStatic fun startStartupPriceFetch() = PRICING.startStartupPriceFetch()
    @JvmStatic fun fetchIdByName(name: String) = PRICING.fetchIdByName(name)
    private fun invalidateDisplayCaches() { DISPLAYS?.invalidate() }

    private fun resolveCounts(mode: String): MutableMap<String, Long> = when (mode) { "daily" -> dailyCounts; "lifetime" -> lifetimeCounts; else -> sessionCounts }
    private fun addDrop(itemName: String, count: Long) {
        if (!isProfitTrackingActive()) return
        var processedName = sanitizeTrackedName(itemName)
        if (processedName.isEmpty()) return
        var multiplier = 1L
        val suffix = COUNT_SUFFIX.matcher(processedName)
        if (suffix.find()) try {
            multiplier = suffix.group(1).toLong()
            processedName = processedName.substring(0, suffix.start()).trim()
        } catch (_: Exception) { }
        val finalCount = count * multiplier
        if (processedName.lowercase().endsWith("vinyl")) processedName = "Vinyl"
        addToAllCounts(PRICING.canonicalizeTrackedName(processedName), finalCount)
    }
    private fun addToAllCounts(key: String, count: Long) {
        checkDailyReset()
        listOf(sessionCounts, dailyCounts, lifetimeCounts).forEach { it[key] = it.getOrDefault(key, 0L) + count }
        invalidateDisplayCaches(); markDirty()
    }
    private fun saveLifetime() = PERSISTENCE.saveLifetime(lifetimeCounts)
    private fun saveDaily() = PERSISTENCE.saveDaily(dailyCounts, sprayDailyQuantity, lastDailyResetDate)
    private fun checkDailyReset() { if (PERSISTENCE.getCurrentDateString() != lastDailyResetDate) resetDaily() }
    private fun sanitizeTrackedName(itemName: String) = TablistUtils.stripColors(itemName).trim()
    @JvmStatic fun isProfitTrackingActive(): Boolean {
        val state = MacroStateManager.getCurrentState()
        return state != MacroState.State.OFF && state != MacroState.State.RECOVERING
    }
}
