package dev.aether.ui.components.tabs

import dev.aether.ui.components.Component
import dev.aether.renderer.NVGRenderer
import java.util.ArrayList

// the parent Tab renders the header; only renderContent runs when this one is active
class SubTab(label: String) {
    private val label: String = label
    private val content = ArrayList<Component>()
    fun addContent(c: Component): SubTab { content.add(c); return this }
    fun renderContent(nvg: NVGRenderer, x: Float, y: Float, width: Float, height: Float) { for (c in content) if (c.isVisible()) c.render(nvg) }
    fun mousePressed(mx: Double, my: Double, btn: Int): Boolean {
        for (i in content.size - 1 downTo 0) { val c = content[i]; if (c.isEnabled() && c.contains(mx, my) && c.mousePressed(mx, my, btn)) return true }
        return false
    }
    fun mouseReleased(mx: Double, my: Double, btn: Int): Boolean { for (c in content) if (c.isEnabled() && c.mouseReleased(mx, my, btn)) return true; return false }
    fun mouseDragged(mx: Double, my: Double, btn: Int, dx: Double, dy: Double): Boolean { for (c in content) if (c.isEnabled() && c.mouseDragged(mx, my, btn, dx, dy)) return true; return false }
    fun mouseScrolled(mx: Double, my: Double, sx: Double, sy: Double): Boolean { for (c in content) if (c.isEnabled() && c.contains(mx, my) && c.mouseScrolled(mx, my, sx, sy)) return true; return false }
    fun keyPressed(key: Int, scan: Int, mods: Int): Boolean { for (c in content) if (c.isEnabled() && c.keyPressed(key, scan, mods)) return true; return false }
    fun charTyped(ch: Char, mods: Int): Boolean { for (c in content) if (c.isEnabled() && c.charTyped(ch, mods)) return true; return false }
    fun getLabel(): String = label
    fun getContent(): MutableList<Component> = content
}
