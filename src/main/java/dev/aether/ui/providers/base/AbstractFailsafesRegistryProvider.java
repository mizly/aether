package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;

public abstract class AbstractFailsafesRegistryProvider implements MainGUIRegistryProvider {
    private static final String SECTION_ID = "failsafes";
    private static final String SECTION_NAME = "Failsafes";
    private static final int SECTION_ORDER = 300;
    private final int order;

    protected AbstractFailsafesRegistryProvider(int order) {
        this.order = order;
    }

    @Override
    public final void register(MainGUIRegistry.Registrar registrar) {
        registrar.registerModuleSection(SECTION_ID, SECTION_NAME, SECTION_ORDER, order, createSubTab());
    }

    protected abstract ModulesTab.SubTab createSubTab();
}
