package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;

public abstract class AbstractModulesRegistryProvider implements MainGUIRegistryProvider {
    private static final String SECTION_ID = "farming";
    private static final String SECTION_NAME = "Farming";
    private static final int SECTION_ORDER = 100;
    private final int order;

    protected AbstractModulesRegistryProvider(int order) {
        this.order = order;
    }

    @Override
    public final void register(MainGUIRegistry.Registrar registrar) {
        registrar.registerModuleSection(SECTION_ID, SECTION_NAME, SECTION_ORDER, order, createSubTab());
    }

    protected abstract ModulesTab.SubTab createSubTab();
}
