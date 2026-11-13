package dev.aether.proxy

import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.config.AetherConfig
import dev.aether.renderer.AetherBackground.Companion.INSTANCE
import dev.aether.util.AetherLang.Companion.localize
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class AetherProxyScreen(parent: Screen) : Screen(Component.literal(localize("Proxy Manager"))) {
    private val parent: Screen = parent

    private var nameField: EditBox? = null
    private var addressField: EditBox? = null
    private var usernameField: EditBox? = null
    private var passwordField: EditBox? = null
    private var enabledBox: Checkbox? = null
    private var typeButton: Button? = null
    private var saveButton: Button? = null
    private var deleteButton: Button? = null
    private var type = AetherProxy.ProxyType.SOCKS5
    private var editingIndex = -1
    private var message = ""

    private var panelX = 0
    private var panelY = 0
    private var listX = 0
    private var listY = 0
    private var formX = 0
    private var formY = 0

    override fun init() {
        panelX = (width - PANEL_W) / 2
        panelY = Math.max(28, (height - 250) / 2)
        listX = panelX
        listY = panelY + 34
        formX = panelX + LIST_W + 28
        formY = panelY + 34

        editingIndex = AetherProxyManager.selectedIndex()
        val proxies = AetherProxyManager.proxies()
        val selected = if (editingIndex >= 0 && editingIndex < proxies.size) proxies[editingIndex] else null

        enabledBox = Checkbox.builder(Component.literal(localize("Enable proxy")), font)
            .pos(formX, formY)
            .selected(AetherProxyManager.isEnabled())
            .build()
        addRenderableWidget(enabledBox!!)

        nameField = field(formX, formY + 36, "Name", if (selected == null) "" else selected.name())
        addressField = field(formX, formY + 76, "IP:PORT", if (selected == null) "" else selected.address())
        usernameField = field(formX, formY + 116, "Username", if (selected == null) "" else selected.username())
        passwordField = field(formX, formY + 156, "Password", if (selected == null) "" else selected.password())
        type = if (selected == null) AetherProxy.ProxyType.SOCKS5 else selected.type()

        typeButton = addRenderableWidget(Button.builder(Component.literal(localize(type.displayName())), { button ->
            type = type.next()
            button.setMessage(Component.literal(localize(type.displayName())))
        }).bounds(formX, formY + 196, 100, 20).build())

        saveButton = addRenderableWidget(Button.builder(Component.literal(localize("Save")), { saveCurrent() })
            .bounds(formX + 110, formY + 196, 100, 20)
            .build())
        deleteButton = addRenderableWidget(Button.builder(Component.literal(localize("Delete")), { deleteCurrent() })
            .bounds(formX + 220, formY + 196, 70, 20)
            .build())
        deleteButton!!.active = editingIndex >= 0

        addRenderableWidget(Button.builder(Component.literal(localize("New")), { newProxy() })
            .bounds(listX, panelY + 4, 64, 20)
            .build())
        addRenderableWidget(Button.builder(Component.literal(localize("Use")), { useCurrent() })
            .bounds(listX + 72, panelY + 4, 64, 20)
            .build())
        addRenderableWidget(Button.builder(Component.literal(localize("Back")), { minecraft.gui.setScreen(parent) })
            .bounds(panelX + PANEL_W - 72, panelY + 4, 72, 20)
            .build())
    }

    private fun field(x: Int, y: Int, hint: String, value: String): EditBox {
        val box = EditBox(font, x, y, FIELD_W, FIELD_H, Component.literal(localize(hint)))
        box.setMaxLength(512)
        box.setValue(value)
        box.setHint(Component.literal(localize(hint)))
        addRenderableWidget(box)
        return box
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (AetherConfig.CUSTOM_UI_ENABLED.get()) {
            INSTANCE.render(width, height, mouseX, mouseY)
        } else {
            super.extractBackground(graphics, mouseX, mouseY, partialTick)
        }
        graphics.centeredText(font, title, width / 2, panelY - 18, 0xFFFFFF)
        drawProxyListBackground(graphics, mouseX, mouseY)
        drawLabels(graphics)
        AetherBootstrapHooks.suspendDisplayTransforms().use {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        }
        drawProxyListText(graphics)
        if (!message.isBlank()) {
            graphics.centeredText(font, message, width / 2, panelY + 266, 0xFFFF5555.toInt())
        }
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
    }

    private fun drawProxyListBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val proxies = AetherProxyManager.proxies()
        graphics.fill(listX, listY, listX + LIST_W, listY + 206, 0x88000000.toInt())
        if (proxies.isEmpty()) {
            return
        }

        for (i in proxies.indices) {
            val y = listY + i * ROW_H
            if (y + ROW_H > listY + 206) {
                break
            }
            val hovered = mouseX >= listX && mouseX <= listX + LIST_W && mouseY >= y && mouseY <= y + ROW_H
            val color = if (i == editingIndex) 0xAA3A3A3A.toInt() else if (hovered) 0x663A3A3A else 0x33000000
            graphics.fill(listX + 2, y + 2, listX + LIST_W - 2, y + ROW_H - 2, color)
        }
    }

    private fun drawProxyListText(graphics: GuiGraphicsExtractor) {
        val proxies = AetherProxyManager.proxies()
        if (proxies.isEmpty()) {
            graphics.centeredText(font, localize("No proxies saved"), listX + LIST_W / 2, listY + 92, 0xFFAAAAAA.toInt())
            return
        }

        val selected = AetherProxyManager.selectedIndex()
        for (i in proxies.indices) {
            val y = listY + i * ROW_H
            if (y + ROW_H > listY + 206) {
                break
            }
            val prefix = if (i == selected && AetherProxyManager.isEnabled()) "* " else ""
            graphics.text(font, prefix + proxies[i].displayName(), listX + 8, y + 5, 0xFFFFFFFF.toInt(), true)
            graphics.text(font, proxies[i].shortStatus(), listX + 8, y + 15, 0xFFB0B0B0.toInt(), true)
        }
    }

    private fun drawLabels(graphics: GuiGraphicsExtractor) {
        graphics.text(font, localize("Name"), formX, formY + 25, 0xAAAAAA, false)
        graphics.text(font, localize("IP:PORT"), formX, formY + 65, 0xAAAAAA, false)
        graphics.text(font, localize("Username"), formX, formY + 105, 0xAAAAAA, false)
        graphics.text(font, localize("Password"), formX, formY + 145, 0xAAAAAA, false)
        graphics.text(font, localize("Type"), formX, formY + 185, 0xAAAAAA, false)
        graphics.text(font, AetherProxyManager.selectedStatus(), listX, listY + 214, 0xAAAAAA, false)
        graphics.text(font, localize("Last used: ") + AetherProxyManager.lastUsedStatus(), listX, listY + 226, 0x777777, false)
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() == 0 && AetherConfig.CUSTOM_UI_ENABLED.get()) {
            INSTANCE.addRipple(click.x().toFloat(), click.y().toFloat())
        }

        if (click.button() == 0 && click.x() >= listX && click.x() <= listX + LIST_W
            && click.y() >= listY && click.y() <= listY + 206) {
            val index = ((click.y() - listY) / ROW_H).toInt()
            if (index >= 0 && index < AetherProxyManager.proxies().size) {
                loadProxy(index)
                return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    private fun loadProxy(index: Int) {
        val proxies = AetherProxyManager.proxies()
        if (index < 0 || index >= proxies.size) {
            return
        }
        val proxy = proxies[index]
        editingIndex = index
        nameField!!.setValue(proxy.name())
        addressField!!.setValue(proxy.address())
        usernameField!!.setValue(proxy.username())
        passwordField!!.setValue(proxy.password())
        type = proxy.type()
        typeButton!!.setMessage(Component.literal(localize(type.displayName())))
        deleteButton!!.active = true
        message = ""
    }

    private fun newProxy() {
        editingIndex = -1
        nameField!!.setValue("")
        addressField!!.setValue("")
        usernameField!!.setValue("")
        passwordField!!.setValue("")
        type = AetherProxy.ProxyType.SOCKS5
        typeButton!!.setMessage(Component.literal(localize(type.displayName())))
        deleteButton!!.active = false
        message = ""
    }

    private fun saveCurrent() {
        val proxy = currentFormProxy()
        if (!proxy.isValid()) {
            message = localize("Proxy address must be host:port.")
            addressField!!.setFocused(true)
            return
        }

        if (editingIndex >= 0) {
            AetherProxyManager.update(editingIndex, proxy)
        } else {
            AetherProxyManager.add(proxy)
            editingIndex = AetherProxyManager.selectedIndex()
            deleteButton!!.active = true
        }
        AetherProxyManager.setEnabled(enabledBox!!.selected())
        AetherProxyManager.refreshProxyStatusButtons(parent)
        message = ""
    }

    private fun useCurrent() {
        saveCurrent()
        if (message.isBlank() && editingIndex >= 0) {
            AetherProxyManager.select(editingIndex)
            AetherProxyManager.setEnabled(true)
            AetherProxyManager.refreshProxyStatusButtons(parent)
            enabledBox = Checkbox.builder(Component.literal(localize("Enable proxy")), font)
                .pos(formX, formY)
                .selected(true)
                .build()
            rebuildWidgets()
        }
    }

    private fun deleteCurrent() {
        if (editingIndex < 0) {
            return
        }
        AetherProxyManager.remove(editingIndex)
        AetherProxyManager.refreshProxyStatusButtons(parent)
        newProxy()
    }

    private fun currentFormProxy(): AetherProxy {
        return AetherProxy(
            nameField!!.value,
            addressField!!.value,
            type,
            usernameField!!.value,
            passwordField!!.value)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean {
        return false
    }

    companion object {
        private const val PANEL_W = 520
        private const val LIST_W = 210
        private const val ROW_H = 24
        private const val FIELD_W = 210
        private const val FIELD_H = 20
    }
}
