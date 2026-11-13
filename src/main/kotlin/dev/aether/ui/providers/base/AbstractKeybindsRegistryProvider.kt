package dev.aether.ui.providers.base

import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.MainGUIRegistryProvider
import dev.aether.ui.settings.ModulesTab

abstract class AbstractKeybindsRegistryProvider(private val order: Int) : MainGUIRegistryProvider {
    final override fun register(registrar: MainGUIRegistry.Registrar) {
        registrar.registerKeybinds(order, createSubTab())
    }

    protected abstract fun createSubTab(): ModulesTab.SubTab
}
