package dev.aether.ui

interface MainGUIRegistryProvider {
    fun register(registrar: MainGUIRegistry.Registrar)
}
