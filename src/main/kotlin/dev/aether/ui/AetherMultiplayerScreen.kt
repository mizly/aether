package dev.aether.ui

import dev.aether.proxy.AetherProxyManager
import dev.aether.proxy.AetherProxyScreen
import dev.aether.renderer.AetherBackground
import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NanoVGManager
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.DirectJoinServerScreen
import net.minecraft.client.gui.screens.ManageServerScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component

// replaces the vanilla JoinMultiplayerScreen via MixinJoinMultiplayerScreen
class AetherMultiplayerScreen(private val lastScreen: Screen) : Screen(Component.literal(AetherLang.localize("Multiplayer"))) {
    private val leftHov = FloatArray(LEFT.size)
    private val rightHov = FloatArray(RIGHT.size)
    private var proxyHover = 0f
    private var servers: ServerList? = null
    private var selectedIndex = -1
    private var scrollOffset = 0f
    private var listX = 0f; private var listY = 0f; private var listW = 0f; private var listH = 0f
    private var leftBtnX = 0f; private var rightBtnX = 0f; private var btnStartY = 0f
    private var proxyX = 0f; private var proxyY = 0f
    private var lastTime = 0L; private var openTime = 0L

    override fun init() {
        servers = ServerList(minecraft)
        servers!!.load()
        selectedIndex = -1
        scrollOffset = 0f
        openTime = System.currentTimeMillis()
        lastTime = openTime
        val footerH = BTN_H * LEFT.size + BTN_GAP * (LEFT.size - 1) + 24f
        listX = 20f; listY = HEADER_H; listW = width - 40f; listH = height - HEADER_H - footerH
        val colGap = 12f
        val totalBtns = BTN_W * 2 + colGap
        leftBtnX = (width - totalBtns) / 2f
        rightBtnX = leftBtnX + BTN_W + colGap
        btnStartY = height - footerH + 12f
        proxyX = width - PROXY_W - 20f; proxyY = 12f; proxyHover = 0f
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, partialTick: Float) {
        val now = System.currentTimeMillis()
        val dt = Math.min((now - lastTime) / 1000f, 0.05f)
        lastTime = now
        val elapsed = now - openTime
        val hspd = Math.min(1f, dt * 8f)
        for (i in LEFT.indices) {
            leftHov[i] += ((if (isOverBtn(mx, my, leftBtnX, i)) 1f else 0f - leftHov[i])) * hspd
            rightHov[i] += ((if (isOverBtn(mx, my, rightBtnX, i)) 1f else 0f - rightHov[i])) * hspd
        }
        val overProxy = mx >= proxyX && mx <= proxyX + PROXY_W && my >= proxyY && my <= proxyY + PROXY_H
        proxyHover += ((if (overProxy) 1f else 0f) - proxyHover) * hspd
        AetherRenderQueue.enqueue { renderQueued(mx, my, elapsed) }
    }

    private fun renderQueued(mx: Int, my: Int, elapsed: Long) {
        if (Minecraft.getInstance().gui.screen() != this) return
        if (!NanoVGManager.isInitialized()) NanoVGManager.init()
        AetherBackground.INSTANCE.render(width, height, mx, my)
        NanoVGManager.beginFrame(width.toFloat(), height.toFloat())
        val nvg = NanoVGManager.getRenderer()!!
        try { drawTitle(nvg, elapsed); drawServerList(nvg, mx, my); drawButtons(nvg); drawProxyButton(nvg) } finally { NanoVGManager.endFrame() }
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {}

    private fun drawTitle(nvg: NVGRenderer, elapsed: Long) {
        val alpha = fadeIn(elapsed, 0, 300)
        if (alpha <= 0f) return
        nvg.save(); nvg.globalAlpha(alpha)
        val accent = Theme.ACCENT_PRIMARY
        val tSize = 36f
        val tW = nvg.textWidth(Fonts.BOLD, "Multiplayer", tSize)
        val tx = (width - tW) / 2f; val ty = (HEADER_H - tSize) / 2f
        nvg.text(Fonts.BOLD, "Multiplayer", tx, ty, tSize, 0xFFFFFFFF.toInt())
        val ulW = tW * Math.min(1f, elapsed / 600f)
        if (ulW > 0f) nvg.rect(tx, ty + tSize + 5f, ulW, 1f, Theme.withAlpha(accent, (alpha * 190).toInt()))
        nvg.restore()
    }

    private fun drawServerList(nvg: NVGRenderer, mx: Int, my: Int) {
        val serverList = servers ?: return
        val count = serverList.size()
        if (count == 0) {
            val msgW = nvg.textWidth(Fonts.REGULAR, "No servers saved.", 12f)
            nvg.text(Fonts.REGULAR, "No servers saved.", (width - msgW) / 2f, listY + listH / 2f - 6f, 12f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0x50))
            return
        }
        nvg.save(); nvg.scissor(listX, listY, listW, listH)
        val rowH = ENTRY_H + ENTRY_PAD
        val totalH = count * rowH - ENTRY_PAD
        val maxScroll = Math.max(0f, totalH - listH)
        scrollOffset = Math.max(0f, Math.min(scrollOffset, maxScroll))
        val accent = Theme.ACCENT_PRIMARY
        for (i in 0 until count) {
            val s = serverList.get(i)
            val ey = listY + i * rowH - scrollOffset
            if (ey + ENTRY_H < listY || ey > listY + listH) continue
            val hovered = mx >= listX && mx <= listX + listW && my >= ey && my <= ey + ENTRY_H
            val selected = i == selectedIndex
            val bgAlpha = if (selected) 0x28 else if (hovered) 0x14 else 0x0A
            nvg.roundedRect(listX, ey, listW, ENTRY_H, 4f, Theme.withAlpha(0xFFFFFFFF.toInt(), bgAlpha))
            if (selected) nvg.roundedRect(listX, ey + 8f, 2f, ENTRY_H - 16f, 1f, Theme.withAlpha(accent, 210))
            nvg.text(Fonts.BOLD, s.name, listX + 14f, ey + 9f, 13f, Theme.withAlpha(0xFFFFFFFF.toInt(), if (selected) 255 else 204))
            nvg.text(Fonts.REGULAR, s.ip, listX + 14f, ey + 26f, 11f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0x55))
        }
        nvg.restore()
    }

    private fun drawButtons(nvg: NVGRenderer) {
        val accent = Theme.ACCENT_PRIMARY
        val hasSel = selectedIndex >= 0
        for (i in LEFT.indices) drawBtn(nvg, LEFT[i], leftBtnX, i, leftHov[i], isLeftEnabled(i, hasSel), accent)
        for (i in RIGHT.indices) drawBtn(nvg, RIGHT[i], rightBtnX, i, rightHov[i], isRightEnabled(i, hasSel), accent)
    }

    private fun drawProxyButton(nvg: NVGRenderer) {
        val accent = Theme.ACCENT_PRIMARY
        val bgAlpha = ((0.11f + proxyHover * 0.09f) * 50).toInt()
        nvg.roundedRect(proxyX, proxyY, PROXY_W, PROXY_H, BTN_R, Theme.withAlpha(0xFFFFFFFF.toInt(), bgAlpha))
        if (proxyHover > 0.01f) {
            nvg.roundedRect(proxyX, proxyY + 5f, 2f, PROXY_H - 10f, 1f, Theme.withAlpha(accent, (proxyHover * 210).toInt()))
            nvg.roundedRect(proxyX + PROXY_W - 2f, proxyY + 5f, 2f, PROXY_H - 10f, 1f, Theme.withAlpha(accent, (proxyHover * 210).toInt()))
        }
        nvg.textCentered(Fonts.REGULAR, AetherProxyManager.selectedStatus(), proxyX, proxyY, PROXY_W, PROXY_H, 12f, Theme.withAlpha(0xFFFFFFFF.toInt(), ((0.72f + proxyHover * 0.28f) * 255).toInt()))
    }

    private fun drawBtn(nvg: NVGRenderer, label: String, bx: Float, row: Int, hov: Float, enabled: Boolean, accent: Int) {
        val by = btnStartY + row * (BTN_H + BTN_GAP)
        val textAlpha = if (enabled) ((0.72f + hov * 0.28f) * 255).toInt() else 0x40
        val bgAlpha = ((0.11f + hov * 0.09f) * 50).toInt()
        nvg.roundedRect(bx, by, BTN_W, BTN_H, BTN_R, Theme.withAlpha(0xFFFFFFFF.toInt(), bgAlpha))
        if (enabled && hov > 0.01f) {
            nvg.roundedRect(bx, by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (hov * 210).toInt()))
            nvg.roundedRect(bx + BTN_W - 2f, by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (hov * 210).toInt()))
        }
        nvg.textCentered(Fonts.REGULAR, label, bx, by, BTN_W, BTN_H, 13f, Theme.withAlpha(0xFFFFFFFF.toInt(), textAlpha))
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() == 0) AetherBackground.INSTANCE.addRipple(click.x().toFloat(), click.y().toFloat())
        if (click.button() != 0) return true
        val mx = click.x(); val my = click.y()
        if (mx >= proxyX && mx <= proxyX + PROXY_W && my >= proxyY && my <= proxyY + PROXY_H) { minecraft.gui.setScreen(AetherProxyScreen(this)); return true }
        val rowH = ENTRY_H + ENTRY_PAD
        val serverList = servers ?: return true
        if (mx >= listX && mx <= listX + listW && my >= listY && my <= listY + listH) {
            val idx = ((my - listY + scrollOffset) / rowH).toInt()
            if (idx >= 0 && idx < serverList.size()) {
                if (idx == selectedIndex && doubled) doJoin() else selectedIndex = idx
                return true
            }
        }
        for (i in LEFT.indices) if (isOverBtn(mx.toInt(), my.toInt(), leftBtnX, i) && isLeftEnabled(i, selectedIndex >= 0)) { handleLeft(i); return true }
        for (i in RIGHT.indices) if (isOverBtn(mx.toInt(), my.toInt(), rightBtnX, i) && isRightEnabled(i, selectedIndex >= 0)) { handleRight(i); return true }
        return true
    }

    override fun mouseScrolled(mx: Double, my: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mx >= listX && mx <= listX + listW && my >= listY && my <= listY + listH) { scrollOffset = Math.max(0f, scrollOffset - (scrollY * 16f).toFloat()); return true }
        return super.mouseScrolled(mx, my, scrollX, scrollY)
    }

    private fun handleLeft(i: Int) { when (i) { 0 -> doJoin(); 1 -> doAdd(); 2 -> doDirectConnect() } }
    private fun handleRight(i: Int) { when (i) { 0 -> doEdit(); 1 -> doDelete(); 2 -> minecraft.gui.setScreen(lastScreen) } }

    private fun doJoin() {
        val serverList = servers ?: return
        if (selectedIndex < 0 || selectedIndex >= serverList.size()) return
        val s = serverList.get(selectedIndex)
        ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(s.ip), s, false, null)
    }

    private fun doAdd() {
        val blank = ServerData("", "", ServerData.Type.OTHER)
        minecraft.gui.setScreen(ManageServerScreen(this, Component.translatable("addServer.title"), { accepted ->
            if (accepted) { servers!!.add(blank, false); servers!!.save() }
            minecraft.gui.setScreen(this); init()
        }, blank))
    }

    private fun doEdit() {
        val serverList = servers ?: return
        if (selectedIndex < 0 || selectedIndex >= serverList.size()) return
        val s = serverList.get(selectedIndex)
        minecraft.gui.setScreen(ManageServerScreen(this, Component.translatable("editServer.title"), { accepted ->
            if (accepted) servers!!.save()
            minecraft.gui.setScreen(this); init()
        }, s))
    }

    private fun doDelete() {
        val serverList = servers ?: return
        if (selectedIndex < 0 || selectedIndex >= serverList.size()) return
        val s = serverList.get(selectedIndex)
        minecraft.gui.setScreen(ConfirmScreen({ accepted ->
            if (accepted) { servers!!.remove(s); servers!!.save(); selectedIndex = -1 }
            minecraft.gui.setScreen(this); init()
        }, Component.translatable("selectServer.deleteQuestion"), Component.literal(s.name)))
    }

    private fun doDirectConnect() {
        val tmp = ServerData("", "", ServerData.Type.OTHER)
        minecraft.gui.setScreen(DirectJoinServerScreen(this, { accepted ->
            if (accepted) ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(tmp.ip), tmp, false, null)
            else minecraft.gui.setScreen(this)
        }, tmp))
    }

    private fun isLeftEnabled(i: Int, hasSel: Boolean): Boolean = when (i) { 0 -> hasSel; else -> true }
    private fun isRightEnabled(i: Int, hasSel: Boolean): Boolean = when (i) { 0, 1 -> hasSel; else -> true }
    private fun isOverBtn(mx: Int, my: Int, bx: Float, row: Int): Boolean {
        val by = btnStartY + row * (BTN_H + BTN_GAP)
        return mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H
    }
    override fun isPauseScreen(): Boolean = false
    override fun children(): List<out GuiEventListener> = java.util.List.of()

    companion object {
        private const val HEADER_H = 52f
        private const val ENTRY_H = 50f
        private const val ENTRY_PAD = 8f
        private const val BTN_W = 200f
        private const val BTN_H = 38f
        private const val BTN_GAP = 8f
        private const val BTN_R = 6f
        private const val PROXY_W = 180f
        private const val PROXY_H = 28f
        private val LEFT = arrayOf("Join Server", "Add Server", "Direct Connect")
        private val RIGHT = arrayOf("Edit Server", "Delete Server", "Back")
        private fun fadeIn(elapsed: Long, startMs: Long, durMs: Long): Float {
            val t = elapsed - startMs
            if (t <= 0L) return 0f
            if (t >= durMs) return 1f
            return t / durMs.toFloat()
        }
    }
}
