package dev.aether.ui;

import dev.aether.ui.settings.ModulesTab;

import java.util.List;

record MainGUIModuleSection(String name, List<ModulesTab.SubTab> subtabs) {
}
