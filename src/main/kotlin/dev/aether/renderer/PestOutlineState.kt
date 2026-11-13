package dev.aether.renderer

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState

class PestOutlineState(private val color: Int, private val originalColor: Int) {
    fun color(): Int = color
    fun originalColor(): Int = originalColor

    override fun equals(other: Any?): Boolean = other is PestOutlineState && color == other.color && originalColor == other.originalColor
    override fun hashCode(): Int = 31 * color + originalColor
    override fun toString(): String = "PestOutlineState[color=$color, originalColor=$originalColor]"

    companion object {
        private val KEY: RenderStateDataKey<PestOutlineState> = RenderStateDataKey.create()

        @JvmStatic fun extract(state: LivingEntityRenderState, color: Int) {
            if (color != 0) {
                state.setData(KEY, PestOutlineState(color, state.outlineColor))
                state.outlineColor = color
            } else if (state.getData(KEY) != null) {
                state.setData(KEY, null)
            }
        }

        @JvmStatic fun submitBody(state: LivingEntityRenderState, submit: Runnable) {
            val outline = state.getData(KEY)
            submitWithColor(state, outline?.originalColor() ?: state.outlineColor, submit)
        }

        @JvmStatic fun submitHead(state: LivingEntityRenderState, submit: Runnable) {
            val outline = state.getData(KEY)
            submitWithColor(state, outline?.color() ?: state.outlineColor, submit)
        }

        private fun submitWithColor(state: LivingEntityRenderState, color: Int, submit: Runnable) {
            val previous = state.outlineColor
            state.outlineColor = color
            try {
                submit.run()
            } finally {
                state.outlineColor = previous
            }
        }
    }
}
