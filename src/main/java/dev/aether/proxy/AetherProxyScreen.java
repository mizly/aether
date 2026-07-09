package dev.aether.proxy;

import dev.aether.config.AetherConfig;
import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.renderer.AetherBackground;
import dev.aether.util.AetherLang;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class AetherProxyScreen extends Screen {
    private static final int PANEL_W = 520;
    private static final int LIST_W = 210;
    private static final int ROW_H = 24;
    private static final int FIELD_W = 210;
    private static final int FIELD_H = 20;

    private final Screen parent;

    private EditBox nameField;
    private EditBox addressField;
    private EditBox usernameField;
    private EditBox passwordField;
    private Checkbox enabledBox;
    private Button typeButton;
    private Button saveButton;
    private Button deleteButton;
    private AetherProxy.ProxyType type = AetherProxy.ProxyType.SOCKS5;
    private int editingIndex = -1;
    private String message = "";

    private int panelX;
    private int panelY;
    private int listX;
    private int listY;
    private int formX;
    private int formY;

    public AetherProxyScreen(Screen parent) {
        super(Component.literal(AetherLang.localize("Proxy Manager")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = Math.max(28, (height - 250) / 2);
        listX = panelX;
        listY = panelY + 34;
        formX = panelX + LIST_W + 28;
        formY = panelY + 34;

        editingIndex = AetherProxyManager.selectedIndex();
        List<AetherProxy> proxies = AetherProxyManager.proxies();
        AetherProxy selected = editingIndex >= 0 && editingIndex < proxies.size() ? proxies.get(editingIndex) : null;

        enabledBox = Checkbox.builder(Component.literal(AetherLang.localize("Enable proxy")), font)
                .pos(formX, formY)
                .selected(AetherProxyManager.isEnabled())
                .build();
        addRenderableWidget(enabledBox);

        nameField = field(formX, formY + 36, "Name", selected == null ? "" : selected.name());
        addressField = field(formX, formY + 76, "IP:PORT", selected == null ? "" : selected.address());
        usernameField = field(formX, formY + 116, "Username", selected == null ? "" : selected.username());
        passwordField = field(formX, formY + 156, "Password", selected == null ? "" : selected.password());
        type = selected == null ? AetherProxy.ProxyType.SOCKS5 : selected.type();

        typeButton = addRenderableWidget(Button.builder(Component.literal(AetherLang.localize(type.displayName())), button -> {
            type = type.next();
            button.setMessage(Component.literal(AetherLang.localize(type.displayName())));
        }).bounds(formX, formY + 196, 100, 20).build());

        saveButton = addRenderableWidget(Button.builder(Component.literal(AetherLang.localize("Save")), button -> saveCurrent())
                .bounds(formX + 110, formY + 196, 100, 20)
                .build());
        deleteButton = addRenderableWidget(Button.builder(Component.literal(AetherLang.localize("Delete")), button -> deleteCurrent())
                .bounds(formX + 220, formY + 196, 70, 20)
                .build());
        deleteButton.active = editingIndex >= 0;

        addRenderableWidget(Button.builder(Component.literal(AetherLang.localize("New")), button -> newProxy())
                .bounds(listX, panelY + 4, 64, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal(AetherLang.localize("Use")), button -> useCurrent())
                .bounds(listX + 72, panelY + 4, 64, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal(AetherLang.localize("Back")), button -> minecraft.setScreen(parent))
                .bounds(panelX + PANEL_W - 72, panelY + 4, 72, 20)
                .build());
    }

    private EditBox field(int x, int y, String hint, String value) {
        EditBox box = new EditBox(font, x, y, FIELD_W, FIELD_H, Component.literal(AetherLang.localize(hint)));
        box.setMaxLength(512);
        box.setValue(value);
        box.setHint(Component.literal(AetherLang.localize(hint)));
        addRenderableWidget(box);
        return box;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (AetherConfig.CUSTOM_UI_ENABLED.get()) {
            AetherBackground.INSTANCE.render(width, height, mouseX, mouseY);
        } else {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
        }
        graphics.centeredText(font, title, width / 2, panelY - 18, 0xFFFFFF);
        drawProxyListBackground(graphics, mouseX, mouseY);
        drawLabels(graphics);
        try (AetherBootstrapHooks.DisplayTransformScope ignored = AetherBootstrapHooks.suspendDisplayTransforms()) {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
        drawProxyListText(graphics);
        if (!message.isBlank()) {
            graphics.centeredText(font, message, width / 2, panelY + 266, 0xFFFF5555);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    private void drawProxyListBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<AetherProxy> proxies = AetherProxyManager.proxies();
        graphics.fill(listX, listY, listX + LIST_W, listY + 206, 0x88000000);
        if (proxies.isEmpty()) {
            return;
        }

        for (int i = 0; i < proxies.size(); i++) {
            int y = listY + i * ROW_H;
            if (y + ROW_H > listY + 206) {
                break;
            }
            boolean hovered = mouseX >= listX && mouseX <= listX + LIST_W && mouseY >= y && mouseY <= y + ROW_H;
            int color = i == editingIndex ? 0xAA3A3A3A : hovered ? 0x663A3A3A : 0x33000000;
            graphics.fill(listX + 2, y + 2, listX + LIST_W - 2, y + ROW_H - 2, color);
        }
    }

    private void drawProxyListText(GuiGraphicsExtractor graphics) {
        List<AetherProxy> proxies = AetherProxyManager.proxies();
        if (proxies.isEmpty()) {
            graphics.centeredText(font, AetherLang.localize("No proxies saved"), listX + LIST_W / 2, listY + 92, 0xFFAAAAAA);
            return;
        }

        int selected = AetherProxyManager.selectedIndex();
        for (int i = 0; i < proxies.size(); i++) {
            int y = listY + i * ROW_H;
            if (y + ROW_H > listY + 206) {
                break;
            }
            String prefix = i == selected && AetherProxyManager.isEnabled() ? "* " : "";
            graphics.text(font, prefix + proxies.get(i).displayName(), listX + 8, y + 5, 0xFFFFFFFF, true);
            graphics.text(font, proxies.get(i).shortStatus(), listX + 8, y + 15, 0xFFB0B0B0, true);
        }
    }

    private void drawLabels(GuiGraphicsExtractor graphics) {
        graphics.text(font, AetherLang.localize("Name"), formX, formY + 25, 0xAAAAAA, false);
        graphics.text(font, AetherLang.localize("IP:PORT"), formX, formY + 65, 0xAAAAAA, false);
        graphics.text(font, AetherLang.localize("Username"), formX, formY + 105, 0xAAAAAA, false);
        graphics.text(font, AetherLang.localize("Password"), formX, formY + 145, 0xAAAAAA, false);
        graphics.text(font, AetherLang.localize("Type"), formX, formY + 185, 0xAAAAAA, false);
        graphics.text(font, AetherProxyManager.selectedStatus(), listX, listY + 214, 0xAAAAAA, false);
        graphics.text(font, AetherLang.localize("Last used: ") + AetherProxyManager.lastUsedStatus(), listX, listY + 226, 0x777777, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0 && AetherConfig.CUSTOM_UI_ENABLED.get()) {
            AetherBackground.INSTANCE.addRipple((float) click.x(), (float) click.y());
        }

        if (click.button() == 0 && click.x() >= listX && click.x() <= listX + LIST_W
                && click.y() >= listY && click.y() <= listY + 206) {
            int index = (int) ((click.y() - listY) / ROW_H);
            if (index >= 0 && index < AetherProxyManager.proxies().size()) {
                loadProxy(index);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void loadProxy(int index) {
        List<AetherProxy> proxies = AetherProxyManager.proxies();
        if (index < 0 || index >= proxies.size()) {
            return;
        }
        AetherProxy proxy = proxies.get(index);
        editingIndex = index;
        nameField.setValue(proxy.name());
        addressField.setValue(proxy.address());
        usernameField.setValue(proxy.username());
        passwordField.setValue(proxy.password());
        type = proxy.type();
            typeButton.setMessage(Component.literal(AetherLang.localize(type.displayName())));
        deleteButton.active = true;
        message = "";
    }

    private void newProxy() {
        editingIndex = -1;
        nameField.setValue("");
        addressField.setValue("");
        usernameField.setValue("");
        passwordField.setValue("");
        type = AetherProxy.ProxyType.SOCKS5;
        typeButton.setMessage(Component.literal(AetherLang.localize(type.displayName())));
        deleteButton.active = false;
        message = "";
    }

    private void saveCurrent() {
        AetherProxy proxy = currentFormProxy();
        if (!proxy.isValid()) {
            message = AetherLang.localize("Proxy address must be host:port.");
            addressField.setFocused(true);
            return;
        }

        if (editingIndex >= 0) {
            AetherProxyManager.update(editingIndex, proxy);
        } else {
            AetherProxyManager.add(proxy);
            editingIndex = AetherProxyManager.selectedIndex();
            deleteButton.active = true;
        }
        AetherProxyManager.setEnabled(enabledBox.selected());
        AetherProxyManager.refreshProxyStatusButtons(parent);
        message = "";
    }

    private void useCurrent() {
        saveCurrent();
        if (message.isBlank() && editingIndex >= 0) {
            AetherProxyManager.select(editingIndex);
            AetherProxyManager.setEnabled(true);
            AetherProxyManager.refreshProxyStatusButtons(parent);
            enabledBox = Checkbox.builder(Component.literal(AetherLang.localize("Enable proxy")), font)
                    .pos(formX, formY)
                    .selected(true)
                    .build();
            rebuildWidgets();
        }
    }

    private void deleteCurrent() {
        if (editingIndex < 0) {
            return;
        }
        AetherProxyManager.remove(editingIndex);
        AetherProxyManager.refreshProxyStatusButtons(parent);
        newProxy();
    }

    private AetherProxy currentFormProxy() {
        return new AetherProxy(
                nameField.getValue(),
                addressField.getValue(),
                type,
                usernameField.getValue(),
                passwordField.getValue());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

