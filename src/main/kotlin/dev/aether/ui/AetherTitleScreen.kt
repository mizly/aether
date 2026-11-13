package dev.aether.ui

import dev.aether.proxy.AetherProxyManager
import dev.aether.proxy.AetherProxyScreen
import dev.aether.renderer.AetherBackground
import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NanoVGManager
import dev.aether.renderer.NVGRenderer
import dev.aether.renderer.NVGScreen
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress

// replaces the vanilla title screen via MixinTitleScreen; all animation runs off wall-clock time
class AetherTitleScreen : NVGScreen("Aether") {
    private var openTime = 0L
    private var lastTime = 0L
    private val hov = FloatArray(LABELS.size)
    private var proxyHover = 0f
    private var cx = 0f
    private var titleY = 0f
    private var versionY = 0f
    private var btnsY = 0f
    private var proxyY = 0f
    private var sepY = 0f

    override fun initNVG() {
        cx = width / 2f
        val totalBtns = LABELS.size * BTN_H + (LABELS.size - 1) * BTN_GAP
        val proxyBlock = AUTH_GAP + PROXY_H
        val blockH = 36f + 10f + 16f + 34f + totalBtns + proxyBlock
        val blockTop = (height - blockH) / 2f
        titleY = blockTop
        versionY = titleY + 52f
        btnsY = versionY + 34f
        proxyY = btnsY + totalBtns + AUTH_GAP
        sepY = height - 38f
        openTime = System.currentTimeMillis()
        lastTime = openTime
        proxyHover = 0f
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, partialTick: Float) {
        if (!NanoVGManager.isInitialized()) NanoVGManager.init()
        val now = System.currentTimeMillis()
        val dtSec = Math.min((now - lastTime) / 1000f, 0.05f)
        lastTime = now
        val elapsed = now - openTime
        val hspd = Math.min(1f, dtSec * 8f)
        for (i in LABELS.indices) {
            val by = btnsY + i * (BTN_H + BTN_GAP)
            val bx = cx - BTN_W / 2f
            val over = mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H
            hov[i] += ((if (over) 1f else 0f) - hov[i]) * hspd
        }
        val proxyX = cx - PROXY_W / 2f
        val overProxy = mx >= proxyX && mx <= proxyX + PROXY_W && my >= proxyY && my <= proxyY + PROXY_H
        proxyHover += ((if (overProxy) 1f else 0f) - proxyHover) * hspd
        AetherRenderQueue.enqueue { renderQueued(mx, my, elapsed) }
    }

    private fun renderQueued(mx: Int, my: Int, elapsed: Long) {
        if (Minecraft.getInstance().gui.screen() != this) return
        AetherBackground.INSTANCE.render(width, height, mx, my)
        NanoVGManager.beginFrame(width.toFloat(), height.toFloat())
        val nvg = NanoVGManager.getRenderer()!!
        try { drawAll(nvg, elapsed) } finally { NanoVGManager.endFrame() }
    }

    private fun drawAll(nvg: NVGRenderer, elapsed: Long) {
        val accent = Theme.ACCENT_PRIMARY
        val aTitle = fadeIn(elapsed, 0, 300)
        if (aTitle > 0f) {
            nvg.save(); nvg.globalAlpha(aTitle)
            val tSize = 36f
            val tW = nvg.textWidth(Fonts.BOLD, "Aether", tSize)
            val tx = cx - tW / 2f
            nvg.text(Fonts.BOLD, "Aether", tx, titleY, tSize, 0xFFFFFFFF.toInt())
            val ulW = tW * Math.min(1f, elapsed / 600f)
            if (ulW > 0f) nvg.rect(tx, titleY + tSize + 5f, ulW, 1f, Theme.withAlpha(accent, (aTitle * 190).toInt()))
            nvg.restore()
        }
        val aVer = fadeIn(elapsed, 80, 250)
        if (aVer > 0f) {
            nvg.save(); nvg.globalAlpha(aVer)
            val vW = nvg.textWidth(Fonts.REGULAR, VERSION, 11f)
            nvg.text(Fonts.REGULAR, VERSION, cx - vW / 2f, versionY, 11f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0x50))
            nvg.restore()
        }
        for (i in LABELS.indices) {
            val aBtn = fadeIn(elapsed, 180 + i * 80L, 220)
            if (aBtn <= 0f) continue
            nvg.save(); nvg.globalAlpha(aBtn); drawButton(nvg, i, accent); nvg.restore()
        }
        val aProxy = fadeIn(elapsed, 520, 220)
        if (aProxy > 0f) { nvg.save(); nvg.globalAlpha(aProxy); drawProxyButton(nvg, accent); nvg.restore() }
        val aBtm = fadeIn(elapsed, 460, 250)
        if (aBtm > 0f) {
            nvg.save(); nvg.globalAlpha(aBtm)
            nvg.rect(0f, sepY, width.toFloat(), 1f, Theme.withAlpha(accent, 0x16))
            val user = AetherLang.localize("Logged in as ") + Minecraft.getInstance().user.name
            nvg.text(Fonts.REGULAR, user, 14f, sepY + (height - sepY - 11f) / 2f, 11f, Theme.withAlpha(0xFFFFFFFF.toInt(), 0x40))
            nvg.restore()
        }
    }

    private fun drawButton(nvg: NVGRenderer, i: Int, accent: Int) {
        val by = btnsY + i * (BTN_H + BTN_GAP)
        val bx = cx - BTN_W / 2f
        val h = hov[i]
        val bgAlpha = ((0.11f + h * 0.09f) * 50).toInt()
        nvg.roundedRect(bx, by, BTN_W, BTN_H, BTN_R, Theme.withAlpha(0xFFFFFFFF.toInt(), bgAlpha))
        if (h > 0.01f) {
            nvg.roundedRect(bx, by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (h * 210).toInt()))
            nvg.roundedRect(bx + BTN_W - 2f, by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (h * 210).toInt()))
        }
        nvg.textCentered(Fonts.REGULAR, LABELS[i], bx, by, BTN_W, BTN_H, 13f, Theme.withAlpha(0xFFFFFFFF.toInt(), ((0.72f + h * 0.28f) * 255).toInt()))
    }

    private fun drawProxyButton(nvg: NVGRenderer, accent: Int) {
        val bx = cx - PROXY_W / 2f
        val bgAlpha = ((0.10f + proxyHover * 0.08f) * 50).toInt()
        nvg.roundedRect(bx, proxyY, PROXY_W, PROXY_H, BTN_R, Theme.withAlpha(0xFFFFFFFF.toInt(), bgAlpha))
        if (proxyHover > 0.01f) {
            nvg.roundedRect(bx, proxyY + 5f, 2f, PROXY_H - 10f, 1f, Theme.withAlpha(accent, (proxyHover * 210).toInt()))
            nvg.roundedRect(bx + PROXY_W - 2f, proxyY + 5f, 2f, PROXY_H - 10f, 1f, Theme.withAlpha(accent, (proxyHover * 210).toInt()))
        }
        nvg.textCentered(Fonts.REGULAR, AetherProxyManager.selectedStatus(), bx, proxyY, PROXY_W, PROXY_H, 12f, Theme.withAlpha(0xFFFFFFFF.toInt(), ((0.72f + proxyHover * 0.28f) * 255).toInt()))
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() == 0) AetherBackground.INSTANCE.addRipple(click.x().toFloat(), click.y().toFloat())
        if (click.button() != 0) return true
        val mx = click.x(); val my = click.y()
        for (i in LABELS.indices) {
            val by = btnsY + i * (BTN_H + BTN_GAP)
            val bx = cx - BTN_W / 2f
            if (mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H) { handleClick(i); return true }
        }
        val proxyX = cx - PROXY_W / 2f
        if (mx >= proxyX && mx <= proxyX + PROXY_W && my >= proxyY && my <= proxyY + PROXY_H) {
            Minecraft.getInstance().gui.setScreen(AetherProxyScreen(this)); return true
        }
        return true
    }

    private fun handleClick(i: Int) {
        val mc = Minecraft.getInstance()
        when (i) {
            0 -> mc.gui.setScreen(SelectWorldScreen(this))
            1 -> mc.gui.setScreen(JoinMultiplayerScreen(this))
            2 -> if (mc.allowsMultiplayer()) {
                val address = "mc.hypixel.net"
                ConnectScreen.startConnecting(this, mc, ServerAddress.parseString(address), ServerData("Hypixel", address, ServerData.Type.OTHER), false, null)
            }
            3 -> mc.gui.setScreen(OptionsScreen(this, mc.options))
            4 -> mc.stop()
        }
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private val LABELS = arrayOf("Singleplayer", "Multiplayer", "Hypixel", "Options", "Quit")
        private const val BTN_W = 220f
        private const val BTN_H = 40f
        private const val BTN_GAP = 10f
        private const val BTN_R = 6f
        private const val AUTH_GAP = 12f
        private const val PROXY_W = 220f
        private const val PROXY_H = 34f
        private val VERSION = FabricLoader.getInstance().getModContainer("aether").map { it.metadata.version.friendlyString }.orElse("dev")
        private fun fadeIn(elapsed: Long, startMs: Long, durMs: Long): Float {
            val t = elapsed - startMs
            if (t <= 0L) return 0f
            if (t >= durMs) return 1f
            return t / durMs.toFloat()
        }
    }
}
