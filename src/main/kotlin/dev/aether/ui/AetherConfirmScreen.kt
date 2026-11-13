package dev.aether.ui

import dev.aether.renderer.AetherBackground
import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NanoVGManager
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

// replaces the vanilla ConfirmScreen via MixinConfirmScreen
class AetherConfirmScreen(private val callback: BooleanConsumer, title: Component, private val message: Component) : Screen(title) {
    private var cx = 0f
    private var titleY = 0f
    private var msgY = 0f
    private var btnsY = 0f
    private var leftBtnX = 0f
    private var rightBtnX = 0f
    private var openTime = 0L
    private var lastTime = 0L
    private val hov = FloatArray(2)

    override fun init() {
        cx = width / 2f
        openTime = System.currentTimeMillis()
        lastTime = openTime
        val blockH = 28f + 12f + 14f + 28f + BTN_H
        val blockTop = (height - blockH) / 2f
        titleY = blockTop
        msgY = blockTop + 44f
        btnsY = msgY + 26f
        val totalBtns = BTN_W * 2f + BTN_GAP
        leftBtnX = cx - totalBtns / 2f
        rightBtnX = leftBtnX + BTN_W + BTN_GAP
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, partialTick: Float) {
        if (!NanoVGManager.isInitialized()) NanoVGManager.init()
        val now = System.currentTimeMillis()
        val dt = Math.min((now - lastTime) / 1000f, 0.05f)
        lastTime = now
        val enabled = now - openTime > BUTTON_DELAY_MS
        val hspd = Math.min(1f, dt * 8f)
        hov[0] += ((if (enabled && isOverBtn(mx, my, leftBtnX)) 1f else 0f) - hov[0]) * hspd
        hov[1] += ((if (enabled && isOverBtn(mx, my, rightBtnX)) 1f else 0f) - hov[1]) * hspd
        AetherRenderQueue.enqueue { renderQueued(mx, my, enabled) }
    }

    private fun renderQueued(mx: Int, my: Int, enabled: Boolean) {
        if (Minecraft.getInstance().gui.screen() != this) return
        AetherBackground.INSTANCE.render(width, height, mx, my)
        NanoVGManager.beginFrame(width.toFloat(), height.toFloat())
        val nvg = NanoVGManager.getRenderer()!!
        try { drawUI(nvg, enabled) } finally { NanoVGManager.endFrame() }
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {}

    private fun drawUI(nvg: NVGRenderer, enabled: Boolean) {
        val accent = Theme.ACCENT_PRIMARY
        val titleStr = title.getString()
        val tSize = 22f
        val tW = nvg.textWidth(Fonts.BOLD, titleStr, tSize)
        nvg.text(Fonts.BOLD, titleStr, cx - tW / 2f, titleY, tSize, 0xFFFFFFFF.toInt())
        nvg.rect(cx - tW / 2f, titleY + tSize + 4f, tW, 1f, Theme.withAlpha(accent, 0xBE))
        val msgStr = message.getString()
        val mW = nvg.textWidth(Fonts.REGULAR, msgStr, 12f)
        nvg.text(Fonts.REGULAR, msgStr, cx - mW / 2f, msgY, 12f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0x80))
        drawBtn(nvg, "Yes", leftBtnX, hov[0], accent, enabled)
        drawBtn(nvg, "No", rightBtnX, hov[1], accent, enabled)
    }

    private fun drawBtn(nvg: NVGRenderer, label: String, bx: Float, h: Float, accent: Int, enabled: Boolean) {
        val by = btnsY
        val textAlpha = if (enabled) ((0.72f + h * 0.28f) * 255).toInt() else 0x40
        val bgAlpha = if (enabled) ((0.11f + h * 0.09f) * 50).toInt() else 5
        nvg.roundedRect(bx, by, BTN_W, BTN_H, BTN_R, Theme.withAlpha(0xFFFFFFFF.toInt(), bgAlpha))
        if (enabled && h > 0.01f) {
            nvg.roundedRect(bx, by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (h * 210).toInt()))
            nvg.roundedRect(bx + BTN_W - 2f, by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (h * 210).toInt()))
        }
        nvg.textCentered(Fonts.REGULAR, label, bx, by, BTN_W, BTN_H, 13f, Theme.withAlpha(0xFFFFFFFF.toInt(), textAlpha))
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() == 0) AetherBackground.INSTANCE.addRipple(click.x().toFloat(), click.y().toFloat())
        if (click.button() != 0) return true
        val enabled = System.currentTimeMillis() - openTime > BUTTON_DELAY_MS
        if (!enabled) return true
        val mx = click.x().toInt()
        val my = click.y().toInt()
        if (isOverBtn(mx, my, leftBtnX)) { callback.accept(true); return true }
        if (isOverBtn(mx, my, rightBtnX)) { callback.accept(false); return true }
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val key = input.key()
        val enabled = System.currentTimeMillis() - openTime > BUTTON_DELAY_MS
        if (!enabled) return true
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_N) { callback.accept(false); return true }
        if (key == InputConstants.KEY_Y) { callback.accept(true); return true }
        return true
    }

    private fun isOverBtn(mx: Int, my: Int, bx: Float): Boolean = mx >= bx && mx <= bx + BTN_W && my >= btnsY && my <= btnsY + BTN_H
    override fun isPauseScreen(): Boolean = false
    override fun children(): List<out GuiEventListener> = java.util.List.of()

    companion object {
        private const val BTN_W = 154f
        private const val BTN_H = 36f
        private const val BTN_GAP = 12f
        private const val BTN_R = 6f
        private const val BUTTON_DELAY_MS = 1000L
    }
}
