package dev.aether.ui

import dev.aether.renderer.AetherBackground
import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NanoVGManager
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.ui.util.TextInput
import dev.aether.util.AetherLang
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

// replaces the vanilla ManageServerScreen via MixinManageServerScreen
class AetherManageServerScreen(private val lastScreen: Screen, title: Component, private val callback: BooleanConsumer, private val serverData: ServerData) : Screen(title) {
    private val screenTitle = title.getString()
    private val nameInput = TextInput()
    private val ipInput = TextInput()
    private var focused: TextInput? = null
    private var cx = 0f
    private var titleY = 0f
    private var nameLabelY = 0f
    private var nameFieldY = 0f
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
        val blockH = 28f + 10f + LABEL_H + FIELD_H + SECTION_G + LABEL_H + FIELD_H + 20f + BTN_H
        val blockTop = (height - blockH) / 2f
        titleY = blockTop
        nameLabelY = blockTop + 38f
        nameFieldY = nameLabelY + LABEL_H
        ipLabelY = nameFieldY + FIELD_H + SECTION_G
        ipFieldY = ipLabelY + LABEL_H
        btnsY = ipFieldY + FIELD_H + 20f
        val totalBtns = BTN_W * 2f + BTN_GAP
        leftBtnX = cx - totalBtns / 2f
        rightBtnX = leftBtnX + BTN_W + BTN_GAP
        nameInput.setMaxLength(128)
        nameInput.setValue(serverData.name)
        ipInput.setMaxLength(128)
        ipInput.setValue(serverData.ip)
        focusField(nameInput)
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
        val tW = nvg.textWidth(Fonts.BOLD, screenTitle, tSize)
        nvg.text(Fonts.BOLD, screenTitle, cx - tW / 2f, titleY, tSize, 0xFFFFFFFF.toInt())
        nvg.rect(cx - tW / 2f, titleY + tSize + 4f, tW, 1f, Theme.withAlpha(accent, 0xBE))
        nvg.text(Fonts.REGULAR, AetherLang.localize("Server Name"), fx, nameLabelY, 10f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0x80))
        drawFieldBg(nvg, fx, nameFieldY, accent, nameInput.isFocused())
        nameInput.render(nvg, fx, nameFieldY, FIELD_W, FIELD_H, 12f, Theme.withAlpha(0xFFFFFFFF.toInt(), if (nameInput.isFocused()) 0xFF else 0xCC))
        nvg.text(Fonts.REGULAR, AetherLang.localize("Server IP"), fx, ipLabelY, 10f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0x80))
        drawFieldBg(nvg, fx, ipFieldY, accent, ipInput.isFocused())
        ipInput.render(nvg, fx, ipFieldY, FIELD_W, FIELD_H, 12f, Theme.withAlpha(0xFFFFFFFF.toInt(), if (ipInput.isFocused()) 0xFF else 0xCC))
        drawBtn(nvg, AetherLang.localize("Done"), leftBtnX, hov[0], accent)
        drawBtn(nvg, AetherLang.localize("Cancel"), rightBtnX, hov[1], accent)
    }

    private fun drawFieldBg(nvg: NVGRenderer, x: Float, y: Float, accent: Int, active: Boolean) {
        nvg.roundedRect(x, y, FIELD_W, FIELD_H, 4f, Theme.withAlpha(0xFFFFFFFF.toInt(), if (active) 0x18 else 0x10))
        nvg.roundedRect(x, y + FIELD_H - 1f, FIELD_W, 1f, 0f, Theme.withAlpha(accent, if (active) 0xCC else 0x60))
    }

    private fun drawBtn(nvg: NVGRenderer, label: String, bx: Float, h: Float, accent: Int) {
        val by = btnsY
        val textAlpha = ((0.72f + h * 0.28f) * 255).toInt()
        val bgAlpha = ((0.11f + h * 0.09f) * 50).toInt()
        nvg.roundedRect(bx, by, BTN_W, BTN_H, BTN_R, Theme.withAlpha(0xFFFFFFFF.toInt(), bgAlpha))
        if (h > 0.01f) {
            nvg.roundedRect(bx, by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (h * 210).toInt()))
            nvg.roundedRect(bx + BTN_W - 2f, by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (h * 210).toInt()))
        }
        nvg.textCentered(Fonts.REGULAR, label, bx, by, BTN_W, BTN_H, 13f, Theme.withAlpha(0xFFFFFFFF.toInt(), textAlpha))
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() == 0) AetherBackground.INSTANCE.addRipple(click.x().toFloat(), click.y().toFloat())
        if (click.button() != 0) return true
        val mx = click.x()
        val my = click.y()
        if (isOverBtn(mx.toInt(), my.toInt(), leftBtnX)) { doDone(); return true }
        if (isOverBtn(mx.toInt(), my.toInt(), rightBtnX)) { doCancel(); return true }
        val fx = cx - FIELD_W / 2f
        if (mx >= fx && mx <= fx + FIELD_W) {
            if (my >= nameFieldY && my <= nameFieldY + FIELD_H) { focusField(nameInput); return true }
            if (my >= ipFieldY && my <= ipFieldY + FIELD_H) { focusField(ipInput); return true }
        }
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val key = input.key()
        if (key == InputConstants.KEY_ESCAPE) { doCancel(); return true }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) { doDone(); return true }
        if (key == InputConstants.KEY_TAB) { focusField(if (nameInput.isFocused()) ipInput else nameInput); return true }
        focused?.keyPressed(key, input.modifiers())
        return true
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        focused?.charTyped(input.codepoint().toChar())
        return true
    }

    private fun focusField(field: TextInput) {
        focused?.setFocused(false)
        focused = field
        field.setFocused(true)
    }

    private fun isOverBtn(mx: Int, my: Int, bx: Float): Boolean = mx >= bx && mx <= bx + BTN_W && my >= btnsY && my <= btnsY + BTN_H
    private fun doDone() { serverData.name = nameInput.getValue(); serverData.ip = ipInput.getValue(); callback.accept(true) }
    private fun doCancel() { callback.accept(false) }
    override fun isPauseScreen(): Boolean = false
    override fun children(): List<out GuiEventListener> = java.util.List.of()

    companion object {
        private const val FIELD_W = 320f
        private const val FIELD_H = 32f
        private const val LABEL_H = 18f
        private const val SECTION_G = 20f
        private const val BTN_W = 154f
        private const val BTN_H = 36f
        private const val BTN_GAP = 12f
        private const val BTN_R = 6f
    }
}
