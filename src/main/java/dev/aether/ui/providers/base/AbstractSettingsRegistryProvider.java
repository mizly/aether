package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;

public abstract class AbstractSettingsRegistryProvider implements MainGUIRegistryProvider {
    private final int order;

    protected AbstractSettingsRegistryProvider(int order) {
        this.order = order;
    }

    @Override
    public final void register(MainGUIRegistry.Registrar registrar) {
        registrar.registerSettings(order, createSubTab());
    }

    protected abstract ModulesTab.SubTab createSubTab();
}
