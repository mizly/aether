package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;

public abstract class AbstractColorsRegistryProvider implements MainGUIRegistryProvider {
    private final int order;

    protected AbstractColorsRegistryProvider(int order) {
        this.order = order;
    }

    @Override
    public final void register(MainGUIRegistry.Registrar registrar) {
        registrar.registerColors(order, createSubTab());
    }

    protected abstract ModulesTab.SubTab createSubTab();
}
