package dev.aether.ui.providers.base;

import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.MainGUIRegistryProvider;
import dev.aether.ui.settings.ModulesTab;

public abstract class AbstractFishingRegistryProvider implements MainGUIRegistryProvider {
    private static final String SECTION_ID = "fishing";
    private static final String SECTION_NAME = "Fishing";
    private static final int SECTION_ORDER = 120;
    private final int order;

    protected AbstractFishingRegistryProvider(int order) {
        this.order = order;
    }

    @Override
    public final void register(MainGUIRegistry.Registrar registrar) {
        registrar.registerModuleSection(SECTION_ID, SECTION_NAME, SECTION_ORDER, order, createSubTab());
    }

    protected abstract ModulesTab.SubTab createSubTab();
}
