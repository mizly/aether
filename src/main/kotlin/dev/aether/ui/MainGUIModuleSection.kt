package dev.aether.ui

import dev.aether.ui.settings.ModulesTab

class MainGUIModuleSection(private val name: String, private val subtabs: List<ModulesTab.SubTab>) {
    fun name(): String = name

    fun subtabs(): List<ModulesTab.SubTab> = subtabs

    override fun equals(other: Any?): Boolean =
        this === other || (other is MainGUIModuleSection && name == other.name && subtabs == other.subtabs)

    override fun hashCode(): Int = 31 * name.hashCode() + subtabs.hashCode()

    override fun toString(): String = "MainGUIModuleSection[name=$name, subtabs=$subtabs]"
}
