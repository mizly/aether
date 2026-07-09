package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;

public abstract class AbstractMiningRegistryProvider implements MainGUIRegistryProvider {
    private static final String SECTION_ID = "other";
    private static final String SECTION_NAME = "Other";
    private static final int SECTION_ORDER = 150;
    private final int order;

    protected AbstractMiningRegistryProvider(int order) {
        this.order = order;
    }

    @Override
    public final void register(MainGUIRegistry.Registrar registrar) {
        registrar.registerModuleSection(SECTION_ID, SECTION_NAME, SECTION_ORDER, order, createSubTab());
    }

    protected abstract ModulesTab.SubTab createSubTab();
}
