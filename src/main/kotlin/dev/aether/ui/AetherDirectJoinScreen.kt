package dev.aether.ui

import dev.aether.renderer.AetherBackground
import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NanoVGManager
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.ui.util.TextInput
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.chat.Component

// replaces the vanilla DirectJoinServerScreen via MixinDirectJoinServerScreen
class AetherDirectJoinScreen(private val lastScreen: Screen, private val callback: BooleanConsumer, private val serverData: ServerData) : Screen(Component.translatable("selectServer.direct")) {
    private val ipInput = TextInput()
    private var cx = 0f
    private var titleY = 0f
    private var ipLabelY = 0f
    private var ipFieldY = 0f
    private var btnsY = 0f
    private var leftBtnX = 0f
    private var rightBtnX = 0f
    private var lastTime = 0L
    private val hov = FloatArray(2)

    override fun init() {
        cx = width / 2f
        lastTime = System.currentTimeMillis()
        val blockH = 28f + 10f + LABEL_H + FIELD_H + 20f + BTN_H
        val blockTop = (height - blockH) / 2f
        titleY = blockTop
        ipLabelY = blockTop + 38f
        ipFieldY = ipLabelY + LABEL_H
        btnsY = ipFieldY + FIELD_H + 20f
        val totalBtns = BTN_W * 2f + BTN_GAP
        leftBtnX = cx - totalBtns / 2f
        rightBtnX = leftBtnX + BTN_W + BTN_GAP
        ipInput.setMaxLength(128)
        ipInput.setValue(serverData.ip)
        ipInput.setFocused(true)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, partialTick: Float) {
        val now = System.currentTimeMillis()
        val dt = Math.min((now - lastTime) / 1000f, 0.05f)
        lastTime = now
        val hspd = Math.min(1f, dt * 8f)
        hov[0] += ((if (isOverBtn(mx, my, leftBtnX)) 1f else 0f) - hov[0]) * hspd
        hov[1] += ((if (isOverBtn(mx, my, rightBtnX)) 1f else 0f) - hov[1]) * hspd
        AetherRenderQueue.enqueue { renderQueued(mx, my) }
    }

    private fun renderQueued(mx: Int, my: Int) {
        if (Minecraft.getInstance().gui.screen() != this) return
        if (!NanoVGManager.isInitialized()) NanoVGManager.init()
        AetherBackground.INSTANCE.render(width, height, mx, my)
        NanoVGManager.beginFrame(width.toFloat(), height.toFloat())
        val nvg = NanoVGManager.getRenderer()!!
        try { drawUI(nvg) } finally { NanoVGManager.endFrame() }
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {}

    private fun drawUI(nvg: NVGRenderer) {
        val accent = Theme.ACCENT_PRIMARY
        val fx = cx - FIELD_W / 2f
        val tSize = 28f
        val tW = nvg.textWidth(Fonts.BOLD, "Direct Connect", tSize)
        nvg.text(Fonts.BOLD, "Direct Connect", cx - tW / 2f, titleY, tSize, 0xFFFFFFFF.toInt())
        nvg.rect(cx - tW / 2f, titleY + tSize + 4f, tW, 1f, Theme.withAlpha(accent, 0xBE))
        nvg.text(Fonts.REGULAR, "Server IP", fx, ipLabelY, 10f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0x80))
        nvg.roundedRect(fx, ipFieldY, FIELD_W, FIELD_H, 4f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0x18))
        nvg.roundedRect(fx, ipFieldY + FIELD_H - 1f, FIELD_W, 1f, 0f, Theme.withAlpha(accent, 0xCC))
        ipInput.render(nvg, fx, ipFieldY, FIELD_W, FIELD_H, 12f, 0xFFFFFFFF.toInt())
        val canJoin = !ipInput.getValue().isBlank()
        drawBtn(nvg, "Join Server", leftBtnX, hov[0], accent, canJoin)
        drawBtn(nvg, "Cancel", rightBtnX, hov[1], accent, true)
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
        val mx = click.x().toInt()
        val my = click.y().toInt()
        if (isOverBtn(mx, my, leftBtnX) && !ipInput.getValue().isBlank()) { doJoin(); return true }
        if (isOverBtn(mx, my, rightBtnX)) { doCancel(); return true }
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val key = input.key()
        if (key == InputConstants.KEY_ESCAPE) { doCancel(); return true }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            if (!ipInput.getValue().isBlank()) doJoin()
            return true
        }
        ipInput.keyPressed(key, input.modifiers())
        return true
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        ipInput.charTyped(input.codepoint().toChar())
        return true
    }

    private fun isOverBtn(mx: Int, my: Int, bx: Float): Boolean = mx >= bx && mx <= bx + BTN_W && my >= btnsY && my <= btnsY + BTN_H
    private fun doJoin() { serverData.ip = ipInput.getValue(); callback.accept(true) }
    private fun doCancel() { callback.accept(false) }
    override fun isPauseScreen(): Boolean = false
    override fun children(): List<out GuiEventListener> = java.util.List.of()

    companion object {
        private const val FIELD_W = 320f
        private const val FIELD_H = 32f
        private const val LABEL_H = 18f
        private const val BTN_W = 154f
        private const val BTN_H = 36f
        private const val BTN_GAP = 12f
        private const val BTN_R = 6f
    }
}
