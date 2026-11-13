package dev.aether.ui

import dev.aether.Aether
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.util.AetherLang
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.ServiceLoader

class MainGUIRegistry private constructor() {
    interface Registrar {
        fun registerModuleSection(sectionId: String?, displayName: String?, sectionOrder: Int, order: Int, subTab: ModulesTab.SubTab?)
        fun registerColors(order: Int, subTab: ModulesTab.SubTab?)
        fun registerKeybinds(order: Int, subTab: ModulesTab.SubTab?)
        fun registerSettings(order: Int, subTab: ModulesTab.SubTab?)
    }

    class ModuleSection(private val idValue: String, private val displayNameValue: String, private val subtabsValue: List<ModulesTab.SubTab>) {
        fun id(): String = idValue
        fun displayName(): String = AetherLang.localize(displayNameValue)
        fun subtabs(): List<ModulesTab.SubTab> = subtabsValue

        override fun equals(other: Any?): Boolean = this === other || other is ModuleSection &&
            idValue == other.idValue && displayNameValue == other.displayNameValue && subtabsValue == other.subtabsValue

        override fun hashCode(): Int = 31 * (31 * idValue.hashCode() + displayNameValue.hashCode()) + subtabsValue.hashCode()
        override fun toString(): String = "ModuleSection[id=$idValue, displayName=$displayNameValue, subtabs=$subtabsValue]"
    }

    private class OrderedSubTab(val order: Int, val subTab: ModulesTab.SubTab)
    private class OrderedModuleSection(val id: String, val displayName: String, val sectionOrder: Int, val subtabs: MutableList<OrderedSubTab>)

    companion object {
        @JvmField val MODULE_SECTIONS: MutableList<ModuleSection> = ArrayList()
        @JvmField val MODULE_SUBTABS: MutableList<ModulesTab.SubTab> = ArrayList()
        @JvmField val COLORS_SUBTABS: MutableList<ModulesTab.SubTab> = ArrayList()
        @JvmField val KEYBINDS_SUBTABS: MutableList<ModulesTab.SubTab> = ArrayList()
        @JvmField val SETTINGS_SUBTABS: MutableList<ModulesTab.SubTab> = ArrayList()
        @JvmField val PROFILES_SUBTABS: MutableList<ModulesTab.SubTab> = java.util.ArrayList(java.util.List.of(
            subTab("Config", "Save and load config profiles", emptyList()),
            subTab("Themes", "Save, load, and import theme profiles", emptyList())
        ))

        private val BOOTSTRAP_PROVIDERS = createBootstrapProviders()
        private var lastServiceClassLoader: ClassLoader? = null
        private var populated = false

        init { refresh() }

        private fun createBootstrapProviders(): List<MainGUIRegistryProvider> = java.util.List.of(BootstrapSettingsRegistryProvider())

        @JvmStatic @Synchronized fun invalidate() {
            lastServiceClassLoader = null
            populated = false
        }

        @JvmStatic @Synchronized fun refresh() {
            val serviceClassLoader: ClassLoader = MainGUIRegistry::class.java.classLoader
            if (serviceClassLoader === lastServiceClassLoader && populated) return
            lastServiceClassLoader = serviceClassLoader

            val moduleSections = LinkedHashMap<String, OrderedModuleSection>()
            val colors = ArrayList<OrderedSubTab>()
            val keybinds = ArrayList<OrderedSubTab>()
            val settings = ArrayList<OrderedSubTab>()
            val registeredProviders = HashSet<String>()
            val registrar = object : Registrar {
                override fun registerModuleSection(sectionId: String?, displayName: String?, sectionOrder: Int, order: Int, subTab: ModulesTab.SubTab?) {
                    if (sectionId.isNullOrBlank() || displayName.isNullOrBlank() || subTab == null) return
                    val section = moduleSections.getOrPut(sectionId) {
                        OrderedModuleSection(sectionId, displayName, sectionOrder, ArrayList())
                    }
                    section.subtabs.add(OrderedSubTab(order, subTab))
                }
                override fun registerColors(order: Int, subTab: ModulesTab.SubTab?) { if (subTab != null) colors.add(OrderedSubTab(order, subTab)) }
                override fun registerKeybinds(order: Int, subTab: ModulesTab.SubTab?) { if (subTab != null) keybinds.add(OrderedSubTab(order, subTab)) }
                override fun registerSettings(order: Int, subTab: ModulesTab.SubTab?) { if (subTab != null) settings.add(OrderedSubTab(order, subTab)) }
            }

            for (provider in BOOTSTRAP_PROVIDERS) registerProvider(provider, registeredProviders, registrar)
            val externalProviders: List<MainGUIRegistryProvider> = try {
                ServiceLoader.load(MainGUIRegistryProvider::class.java, serviceClassLoader).stream().map { it.get() }.toList()
            } catch (e: RuntimeException) {
                Aether.LOGGER.warn("Skipping MainGUI providers due to service-load failure: {}", e.message)
                emptyList()
            } catch (e: LinkageError) {
                Aether.LOGGER.warn("Skipping MainGUI providers due to service-load failure: {}", e.message)
                emptyList()
            }
            for (provider in externalProviders) registerProvider(provider, registeredProviders, registrar)

            publishModuleSections(moduleSections)
            publish(COLORS_SUBTABS, colors)
            publish(KEYBINDS_SUBTABS, keybinds)
            publish(SETTINGS_SUBTABS, settings)
            populated = true
        }

        @JvmStatic fun subTab(name: String, description: String, groups: List<SettingGroup>): ModulesTab.SubTab = ModulesTab.SubTab(name, description, groups)

        @JvmStatic fun toggleSubTab(
            name: String,
            description: String,
            enabledGetter: java.util.function.BooleanSupplier,
            enabledSetter: java.util.function.Consumer<Boolean>,
            groups: List<SettingGroup>
        ): ModulesTab.SubTab = ModulesTab.SubTab(name, description, enabledGetter, enabledSetter, groups)

        private fun publish(target: MutableList<ModulesTab.SubTab>, orderedSubTabs: List<OrderedSubTab>) {
            target.clear()
            orderedSubTabs.sortedBy { it.order }.forEach { target.add(it.subTab) }
        }

        private fun publishModuleSections(orderedSections: Map<String, OrderedModuleSection>) {
            MODULE_SECTIONS.clear()
            MODULE_SUBTABS.clear()
            orderedSections.values.sortedBy { it.sectionOrder }.forEach { section ->
                val subtabs = section.subtabs.sortedBy { it.order }.map { it.subTab }
                if (subtabs.isEmpty()) return@forEach
                MODULE_SECTIONS.add(ModuleSection(section.id, section.displayName, subtabs.toList()))
                MODULE_SUBTABS.addAll(subtabs)
            }
        }

        private fun registerProvider(provider: MainGUIRegistryProvider?, registeredProviders: MutableSet<String>, registrar: Registrar) {
            if (provider == null || !registeredProviders.add(provider.javaClass.name)) return
            try {
                provider.register(registrar)
            } catch (e: RuntimeException) {
                Aether.LOGGER.warn("Skipping MainGUI provider '{}' due to load/register failure: {}", provider.javaClass.name, e.message)
            } catch (e: LinkageError) {
                Aether.LOGGER.warn("Skipping MainGUI provider '{}' due to load/register failure: {}", provider.javaClass.name, e.message)
            }
        }
    }
}
