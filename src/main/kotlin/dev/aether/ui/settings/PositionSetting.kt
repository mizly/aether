package dev.aether.ui.settings

import dev.aether.util.AetherLang
import java.util.ArrayList
import java.util.Collections
import java.util.function.Consumer
import java.util.function.Supplier

// x/y/z coordinates plus a capture button
class PositionSetting(name: String, private val xGetter: Supplier<Double>, private val xSetter: Consumer<Double>, private val yGetter: Supplier<Double>, private val ySetter: Consumer<Double>, private val zGetter: Supplier<Double>, private val zSetter: Consumer<Double>, private val highlightGetter: Supplier<Boolean>, private val highlightSetter: Consumer<Boolean>, private val captureAction: Runnable) : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private val actionButtons: MutableList<ActionButton> = ArrayList()
    private var visibility: Supplier<Boolean> = Supplier { true }
    fun getX(): Double = xGetter.get()
    fun setX(v: Double) = xSetter.accept(v)
    fun getY(): Double = yGetter.get()
    fun setY(v: Double) = ySetter.accept(v)
    fun getZ(): Double = zGetter.get()
    fun setZ(v: Double) = zSetter.accept(v)
    fun isHighlighted(): Boolean = highlightGetter.get()
    fun setHighlighted(v: Boolean) = highlightSetter.accept(v)
    fun capture() = captureAction.run()
    fun getActionButtons(): kotlin.collections.List<ActionButton> = Collections.unmodifiableList(actionButtons)
    fun addActionButton(label: String, action: Runnable?, enabled: Supplier<Boolean>?): PositionSetting { actionButtons.add(ActionButton(label, action, enabled)); return this }
    fun visibleWhen(condition: Supplier<Boolean>): PositionSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getType(): SettingType = SettingType.POSITION
    override fun isVisible(): Boolean = visibility.get()

    class ActionButton(private val label: String, private val action: Runnable?, private val enabled: Supplier<Boolean>?) {
        fun label(): String = label
        fun action(): Runnable? = action
        fun enabled(): Supplier<Boolean>? = enabled
        fun isEnabled(): Boolean = enabled == null || enabled.get()
        fun execute() { if (isEnabled() && action != null) action.run() }
        override fun equals(other: Any?): Boolean = other is ActionButton && label == other.label && action == other.action && enabled == other.enabled
        override fun hashCode(): Int = java.util.Objects.hash(label, action, enabled)
        override fun toString(): String = "ActionButton[label=$label, action=$action, enabled=$enabled]"
    }
}
