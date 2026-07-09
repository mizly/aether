package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;

public abstract class AbstractVisualsRegistryProvider implements MainGUIRegistryProvider {
    private static final String SECTION_ID = "visuals";
    private static final String SECTION_NAME = "Visuals";
    private static final int SECTION_ORDER = 400;
    private final int order;

    protected AbstractVisualsRegistryProvider(int order) {
        this.order = order;
    }

    @Override
    public final void register(MainGUIRegistry.Registrar registrar) {
        registrar.registerModuleSection(SECTION_ID, SECTION_NAME, SECTION_ORDER, order, createSubTab());
    }

    protected abstract ModulesTab.SubTab createSubTab();
}
