package dev.aether.ui.providers.base;

import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.MainGUIRegistryProvider;
import dev.aether.ui.settings.ModulesTab;

public abstract class AbstractFailsafesRegistryProvider implements MainGUIRegistryProvider {
    // general failsafes watch every macro, the other two only their own macro type
    protected enum Category {
        GENERAL("failsafes", "General Failsafes", 300),
        FARMING("failsafes_farming", "Farming Failsafes", 310),
        FISHING("failsafes_fishing", "Fishing Failsafes", 320);

        private final String sectionId;
        private final String sectionName;
        private final int sectionOrder;

        Category(String sectionId, String sectionName, int sectionOrder) {
            this.sectionId = sectionId;
            this.sectionName = sectionName;
            this.sectionOrder = sectionOrder;
        }
    }

    private final Category category;
    private final int order;

    protected AbstractFailsafesRegistryProvider(Category category, int order) {
        this.category = category;
        this.order = order;
    }

    @Override
    public final void register(MainGUIRegistry.Registrar registrar) {
        registrar.registerModuleSection(category.sectionId, category.sectionName, category.sectionOrder, order,
                createSubTab());
    }

    protected abstract ModulesTab.SubTab createSubTab();
}
