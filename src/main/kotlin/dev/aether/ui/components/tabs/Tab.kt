package dev.aether.ui.components.tabs

import dev.aether.ui.components.Component
import dev.aether.renderer.NVGRenderer
import java.util.ArrayList

// the TabBar draws the header; only renderContent runs when this tab is active
class Tab(label: String) {
    private val label: String = label
    private val content = ArrayList<Component>()
    private val subTabs = ArrayList<SubTab>()
    private var subTabBar: SubTabBar? = null
    fun addContent(c: Component): Tab { content.add(c); return this }
    fun addSubTab(subTab: SubTab): Tab { subTabs.add(subTab); return this }
    fun buildSubTabBar(x: Float, y: Float, width: Float): Tab { if (subTabs.isNotEmpty()) subTabBar = SubTabBar(subTabs, x, y, width); return this }
    fun renderContent(nvg: NVGRenderer, x: Float, y: Float, width: Float, height: Float) {
        val bar = subTabBar
        if (bar != null) {
            bar.setBounds(x, y, width, bar.getHeight())
            bar.render(nvg)
            val below = y + bar.getHeight()
            val active = bar.getActiveSubTab()
            if (active != null) active.renderContent(nvg, x, below, width, height - bar.getHeight())
        } else for (c in content) if (c.isVisible()) c.render(nvg)
    }
    fun mousePressed(mx: Double, my: Double, btn: Int): Boolean {
        val bar = subTabBar
        if (bar != null && bar.mousePressed(mx, my, btn)) return true
        val active = bar?.getActiveSubTab()
        if (active != null) return active.mousePressed(mx, my, btn)
        for (i in content.size - 1 downTo 0) { val c = content[i]; if (c.isEnabled() && c.contains(mx, my) && c.mousePressed(mx, my, btn)) return true }
        return false
    }
    fun mouseReleased(mx: Double, my: Double, btn: Int): Boolean {
        subTabBar?.mouseReleased(mx, my, btn)
        val active = subTabBar?.getActiveSubTab()
        if (active != null) return active.mouseReleased(mx, my, btn)
        for (c in content) if (c.isEnabled() && c.mouseReleased(mx, my, btn)) return true
        return false
    }
    fun mouseDragged(mx: Double, my: Double, btn: Int, dx: Double, dy: Double): Boolean {
        val active = subTabBar?.getActiveSubTab()
        if (active != null) return active.mouseDragged(mx, my, btn, dx, dy)
        for (c in content) if (c.isEnabled() && c.mouseDragged(mx, my, btn, dx, dy)) return true
        return false
    }
    fun mouseScrolled(mx: Double, my: Double, sx: Double, sy: Double): Boolean {
        val active = subTabBar?.getActiveSubTab()
        if (active != null) return active.mouseScrolled(mx, my, sx, sy)
        for (c in content) if (c.isEnabled() && c.contains(mx, my) && c.mouseScrolled(mx, my, sx, sy)) return true
        return false
    }
    fun keyPressed(key: Int, scan: Int, mods: Int): Boolean {
        val active = subTabBar?.getActiveSubTab()
        if (active != null) return active.keyPressed(key, scan, mods)
        for (c in content) if (c.isEnabled() && c.keyPressed(key, scan, mods)) return true
        return false
    }
    fun charTyped(ch: Char, mods: Int): Boolean {
        val active = subTabBar?.getActiveSubTab()
        if (active != null) return active.charTyped(ch, mods)
        for (c in content) if (c.isEnabled() && c.charTyped(ch, mods)) return true
        return false
    }
    fun getLabel(): String = label
    fun getSubTabBar(): SubTabBar? = subTabBar
    fun getSubTabs(): MutableList<SubTab> = subTabs
}
