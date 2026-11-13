package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.function.Consumer
import java.util.function.Supplier

// multi-select backed by a bitmask; option N is bit N
class MultiDropdownSetting(name: String, options: List<String>, private val getter: Supplier<Int>, private val setter: Consumer<Int>) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private val options: List<String> = options.map { o -> AetherLang.localize(o) }
    private var visibility: Supplier<Boolean> = Supplier { true }
    fun getOptions(): List<String> = options.toList()
    fun isSelected(index: Int): Boolean = (getter.get() and (1 shl index)) != 0
    fun toggleOption(index: Int) = setter.accept(getter.get() xor (1 shl index))
    fun estimateTotalWidth(): Float { var total = 0f; for (i in options.indices) { total += options[i].length * (CHIP_FONT_SZ * 0.52f) + CHIP_PAD_X * 2f; if (i > 0) total += CHIP_GAP }; return total }
    fun rowCount(availableWidth: Float): Int { val width = Math.max(1f, availableWidth); var used = 0f; var rows = 1; for (option in options) { val chip = chipWidth(option); if (used > 0f && used + CHIP_GAP + chip > width) { rows++; used = chip } else used += (if (used > 0f) CHIP_GAP else 0f) + chip }; return rows }
    fun visibleWhen(condition: Supplier<Boolean>): MultiDropdownSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.MULTI_DROPDOWN
    override fun isVisible(): Boolean = visibility.get()
    companion object {
        const val CHIP_H: Float = 28f
        const val CHIP_PAD_X: Float = 10f
        const val CHIP_GAP: Float = 6f
        const val CHIP_FONT_SZ: Float = 12f
        @JvmStatic fun chipWidth(option: String): Float = option.length * (CHIP_FONT_SZ * 0.52f) + CHIP_PAD_X * 2f
    }
}
