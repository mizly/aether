package dev.aether.ui;

import dev.aether.Aether;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

public class MainGUIRegistry {
    public interface Registrar {
        void registerModuleSection(String sectionId, String displayName, int sectionOrder, int order, ModulesTab.SubTab subTab);
        void registerColors(int order, ModulesTab.SubTab subTab);
        void registerKeybinds(int order, ModulesTab.SubTab subTab);
        void registerSettings(int order, ModulesTab.SubTab subTab);
    }

    public record ModuleSection(String id, String displayName, List<ModulesTab.SubTab> subtabs) {
        public String displayName() {
            return AetherLang.localize(displayName);
        }
    }

    private record OrderedSubTab(int order, ModulesTab.SubTab subTab) {}
    private record OrderedModuleSection(String id, String displayName, int sectionOrder, List<OrderedSubTab> subtabs) {}

    public static final List<ModuleSection> MODULE_SECTIONS = new ArrayList<>();
    public static final List<ModulesTab.SubTab> MODULE_SUBTABS = new ArrayList<>();
    public static final List<ModulesTab.SubTab> COLORS_SUBTABS = new ArrayList<>();
    public static final List<ModulesTab.SubTab> KEYBINDS_SUBTABS = new ArrayList<>();
    public static final List<ModulesTab.SubTab> SETTINGS_SUBTABS = new ArrayList<>();

    public static final List<ModulesTab.SubTab> PROFILES_SUBTABS = List.of(
            subTab("Config", "Save and load config profiles", List.of()),
            subTab("Themes", "Save, load, and import theme profiles", List.of()));

    private static final List<MainGUIRegistryProvider> BOOTSTRAP_PROVIDERS = createBootstrapProviders();

    private static ClassLoader lastServiceClassLoader;
    private static boolean populated;

    static {
        refresh();
    }

    private static List<MainGUIRegistryProvider> createBootstrapProviders() {
        List<MainGUIRegistryProvider> providers = new ArrayList<>();
        providers.add(new BootstrapSettingsRegistryProvider());
        return List.copyOf(providers);
    }

    public static synchronized void invalidate() {
        lastServiceClassLoader = null;
        populated = false;
    }

    public static synchronized void refresh() {
        ClassLoader serviceClassLoader = MainGUIRegistry.class.getClassLoader();
        if (serviceClassLoader == lastServiceClassLoader && populated) {
            return;
        }

        lastServiceClassLoader = serviceClassLoader;

        Map<String, OrderedModuleSection> moduleSections = new LinkedHashMap<>();
        List<OrderedSubTab> colors = new ArrayList<>();
        List<OrderedSubTab> keybinds = new ArrayList<>();
        List<OrderedSubTab> settings = new ArrayList<>();
        Set<String> registeredProviders = new HashSet<>();

        Registrar registrar = new Registrar() {
            @Override
            public void registerModuleSection(String sectionId, String displayName, int sectionOrder, int order, ModulesTab.SubTab subTab) {
                if (sectionId == null || sectionId.isBlank() || displayName == null || displayName.isBlank() || subTab == null) {
                    return;
                }

                OrderedModuleSection section = moduleSections.computeIfAbsent(
                        sectionId,
                        id -> new OrderedModuleSection(id, displayName, sectionOrder, new ArrayList<>()));
                section.subtabs().add(new OrderedSubTab(order, subTab));
            }

            @Override
            public void registerColors(int order, ModulesTab.SubTab subTab) {
                colors.add(new OrderedSubTab(order, subTab));
            }

            @Override
            public void registerKeybinds(int order, ModulesTab.SubTab subTab) {
                keybinds.add(new OrderedSubTab(order, subTab));
            }

            @Override
            public void registerSettings(int order, ModulesTab.SubTab subTab) {
                settings.add(new OrderedSubTab(order, subTab));
            }
        };

        for (MainGUIRegistryProvider provider : BOOTSTRAP_PROVIDERS) {
            registerProvider(provider, registeredProviders, registrar);
        }

        List<MainGUIRegistryProvider> externalProviders;
        try {
            externalProviders = ServiceLoader.load(MainGUIRegistryProvider.class, serviceClassLoader)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .toList();
        } catch (RuntimeException | LinkageError e) {
            Aether.LOGGER.warn("Skipping MainGUI providers due to service-load failure: {}", e.getMessage());
            externalProviders = List.of();
        }

        for (MainGUIRegistryProvider provider : externalProviders) {
            registerProvider(provider, registeredProviders, registrar);
        }

        publishModuleSections(MODULE_SECTIONS, MODULE_SUBTABS, moduleSections);
        publish(COLORS_SUBTABS, colors);
        publish(KEYBINDS_SUBTABS, keybinds);
        publish(SETTINGS_SUBTABS, settings);
        populated = true;
    }

    public static ModulesTab.SubTab subTab(String name, String description, List<SettingGroup> groups) {
        return new ModulesTab.SubTab(name, description, groups);
    }

    public static ModulesTab.SubTab toggleSubTab(
            String name,
            String description,
            java.util.function.BooleanSupplier enabledGetter,
            java.util.function.Consumer<Boolean> enabledSetter,
            List<SettingGroup> groups) {
        return new ModulesTab.SubTab(name, description, enabledGetter, enabledSetter, groups);
    }

    private static void publish(List<ModulesTab.SubTab> target, List<OrderedSubTab> orderedSubTabs) {
        target.clear();
        orderedSubTabs.stream()
                .sorted(Comparator.comparingInt(OrderedSubTab::order))
                .map(OrderedSubTab::subTab)
                .forEach(target::add);
    }

    private static void publishModuleSections(
            List<ModuleSection> targetSections,
            List<ModulesTab.SubTab> targetSubtabs,
            Map<String, OrderedModuleSection> orderedSections) {
        targetSections.clear();
        targetSubtabs.clear();

        orderedSections.values().stream()
                .sorted(Comparator.comparingInt(OrderedModuleSection::sectionOrder))
                .forEach(section -> {
                    List<ModulesTab.SubTab> subtabs = section.subtabs().stream()
                            .sorted(Comparator.comparingInt(OrderedSubTab::order))
                            .map(OrderedSubTab::subTab)
                            .toList();
                    if (subtabs.isEmpty()) {
                        return;
                    }

                    targetSections.add(new ModuleSection(section.id(), section.displayName(), List.copyOf(subtabs)));
                    targetSubtabs.addAll(subtabs);
                });
    }

    private static void registerProvider(MainGUIRegistryProvider provider, Set<String> registeredProviders, Registrar registrar) {
        if (provider == null || !registeredProviders.add(provider.getClass().getName())) {
            return;
        }
        try {
            provider.register(registrar);
        } catch (RuntimeException | LinkageError e) {
            Aether.LOGGER.warn("Skipping MainGUI provider '{}' due to load/register failure: {}",
                    provider.getClass().getName(),
                    e.getMessage());
        }
    }
}

