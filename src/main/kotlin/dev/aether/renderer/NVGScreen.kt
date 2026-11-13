package dev.aether.renderer

import com.mojang.blaze3d.platform.InputConstants
import dev.aether.ui.components.Component
import dev.aether.util.AetherLang
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import java.util.ArrayList

abstract class NVGScreen(title: String) : Screen(net.minecraft.network.chat.Component.literal(AetherLang.localize(title))) {
    private val components: MutableList<Component> = ArrayList()

    override fun init() {
        components.clear()
        initNVG()
    }

    protected open fun initNVG() {}

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        AetherRenderQueue.enqueue { renderQueued() }
    }

    private fun renderQueued() {
        if (net.minecraft.client.Minecraft.getInstance().gui.screen() !== this) return
        if (!NanoVGManager.isInitialized()) NanoVGManager.init()
        NanoVGManager.beginFrame(width.toFloat(), height.toFloat())
        val nvg = NanoVGManager.getRenderer()!!
        try {
            renderNVG(nvg)
            for (component in components) if (component.isVisible()) component.render(nvg)
        } finally {
            NanoVGManager.endFrame()
        }
    }

    protected open fun renderNVG(nvg: NVGRenderer) {}

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = click.x()
        val my = click.y()
        val btn = click.button()
        for (i in components.size - 1 downTo 0) {
            val component = components[i]
            if (component.isVisible() && component.isEnabled() && component.contains(mx, my)) {
                if (component.mousePressed(mx, my, btn)) return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val mx = click.x()
        val my = click.y()
        val btn = click.button()
        for (i in components.size - 1 downTo 0) {
            val component = components[i]
            if (component.isVisible() && component.isEnabled() && component.mouseReleased(mx, my, btn)) return true
        }
        return super.mouseReleased(click)
    }

    override fun mouseDragged(click: MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean {
        val mx = click.x()
        val my = click.y()
        val btn = click.button()
        for (i in components.size - 1 downTo 0) {
            val component = components[i]
            if (component.isVisible() && component.isEnabled() && component.mouseDragged(mx, my, btn, deltaX, deltaY)) return true
        }
        return super.mouseDragged(click, deltaX, deltaY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        for (i in components.size - 1 downTo 0) {
            val component = components[i]
            if (component.isVisible() && component.isEnabled() && component.contains(mouseX, mouseY) && component.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val key = input.key()
        val scan = input.key()
        val mods = input.modifiers()
        for (i in components.size - 1 downTo 0) {
            val component = components[i]
            if (component.isVisible() && component.isEnabled() && component.keyPressed(key, scan, mods)) return true
        }
        if (key == InputConstants.KEY_ESCAPE) {
            onClose()
            return true
        }
        return false
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        val ch = input.codepoint().toChar()
        val mods = 0
        for (i in components.size - 1 downTo 0) {
            val component = components[i]
            if (component.isVisible() && component.isEnabled() && component.charTyped(ch, mods)) return true
        }
        return super.charTyped(input)
    }

    override fun isPauseScreen(): Boolean = false
    override fun extractBackground(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {}
    override fun children(): List<out net.minecraft.client.gui.components.events.GuiEventListener> = java.util.List.of()

    protected fun <T : Component> addComponent(component: T): T {
        components.add(component)
        return component
    }

    protected fun removeComponent(component: Component) { components.remove(component) }
    protected fun getComponents(): MutableList<Component> = components
}
