package dev.aether.ui.providers.visuals

import dev.aether.config.AetherConfig
import dev.aether.modules.visuals.PestDefeatEffects
import dev.aether.ui.MainGUIRegistry
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider
import dev.aether.ui.settings.ActionSetting
import dev.aether.ui.settings.ColorSetting
import dev.aether.ui.settings.DropdownSetting
import dev.aether.ui.settings.ModulesTab
import dev.aether.ui.settings.SettingGroup
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.ToggleSetting
import java.util.ArrayList

class FunVisualsRegistryProvider : AbstractVisualsRegistryProvider(3) {
    override fun createSubTab(): ModulesTab.SubTab {
        val groups: MutableList<SettingGroup> = java.util.ArrayList()
        groups.add(SettingGroup.of("Pest Defeat Effects", "Shader bursts when a pest is defeated or caught",
            AetherConfig.PEST_DEFEAT_EFFECTS::get) { value -> AetherConfig.PEST_DEFEAT_EFFECTS.set(value); AetherConfig.save() }
            .add(DropdownSetting("Effect Style", listOf("Ender Rift", "Arcane Bloom", "Solar Flare"), AetherConfig.PEST_DEFEAT_STYLE::get) { value -> AetherConfig.PEST_DEFEAT_STYLE.set(value); AetherConfig.save() })
            .add(SliderSetting("Effect Size", 0.5f, 2f, AetherConfig.PEST_DEFEAT_SCALE::get) { value -> AetherConfig.PEST_DEFEAT_SCALE.set(value); AetherConfig.save() }.withDecimals(1))
            .add(SliderSetting("Particle Count", 8.0f, 24.0f, { AetherConfig.PEST_DEFEAT_PARTICLES.get().toFloat() }) { value -> AetherConfig.PEST_DEFEAT_PARTICLES.set(Math.round(value).toInt()); AetherConfig.save() }.withDecimals(0))
            .add(ActionSetting("Preview Effect", PestDefeatEffects::preview)))
        groups.add(SettingGroup.of("Dragon Wings", "Animated dragon wings on your back in third person",
            AetherConfig.DRAGON_WINGS_ENABLED::get) { value -> AetherConfig.DRAGON_WINGS_ENABLED.set(value); AetherConfig.save() }
            .add(ToggleSetting("Wireframe", AetherConfig.DRAGON_WINGS_WIREFRAME::get) { value -> AetherConfig.DRAGON_WINGS_WIREFRAME.set(value); AetherConfig.save() })
            .add(SliderSetting("Wingspan", 0.5f, 1.4f, AetherConfig.DRAGON_WINGS_SCALE::get) { value -> AetherConfig.DRAGON_WINGS_SCALE.set(value); AetherConfig.save() }.withDecimals(2))
            .add(SliderSetting("Wing Animation Speed", 0.4f, 2f, AetherConfig.DRAGON_WINGS_SPEED::get) { value -> AetherConfig.DRAGON_WINGS_SPEED.set(value); AetherConfig.save() }.withDecimals(1))
            .add(ColorSetting("Wing Accent", AetherConfig.DRAGON_WINGS_COLOR::get) { value -> AetherConfig.DRAGON_WINGS_COLOR.set(value); AetherConfig.save() })
            .add(ToggleSetting("Membrane Glow", AetherConfig.DRAGON_WINGS_GLOW::get) { value -> AetherConfig.DRAGON_WINGS_GLOW.set(value); AetherConfig.save() }.visibleWhen { !AetherConfig.DRAGON_WINGS_WIREFRAME.get() }))
        groups.add(SettingGroup.of("Aether Cape", "Your Aether logo cape with vanilla movement", AetherConfig.CAPE_ENABLED::get) { value -> AetherConfig.CAPE_ENABLED.set(value); AetherConfig.save() })
        groups.add(SettingGroup.of("Halo", "A softly glowing halo above your head in third person", AetherConfig.HALO_ENABLED::get) { value -> AetherConfig.HALO_ENABLED.set(value); AetherConfig.save() }
            .add(DropdownSetting("Halo Style", listOf("Classic", "Orbit", "Radiant"), AetherConfig.HALO_STYLE::get) { value -> AetherConfig.HALO_STYLE.set(value); AetherConfig.save() })
            .add(ColorSetting("Halo Color", AetherConfig.HALO_COLOR::get) { value -> AetherConfig.HALO_COLOR.set(value); AetherConfig.save() })
            .add(SliderSetting("Halo Size", 0.7f, 1.5f, AetherConfig.HALO_SCALE::get) { value -> AetherConfig.HALO_SCALE.set(value); AetherConfig.save() }.withDecimals(1))
            .add(SliderSetting("Halo Height", 0.12f, 0.7f, AetherConfig.HALO_HEIGHT::get) { value -> AetherConfig.HALO_HEIGHT.set(value); AetherConfig.save() }.withDecimals(2))
            .add(SliderSetting("Halo Tilt", -25.0f, 25.0f, AetherConfig.HALO_TILT::get) { value -> AetherConfig.HALO_TILT.set(value); AetherConfig.save() }.withDecimals(0))
            .add(SliderSetting("Halo Glow", 0.0f, 1.0f, AetherConfig.HALO_GLOW::get) { value -> AetherConfig.HALO_GLOW.set(value); AetherConfig.save() }.withDecimals(1))
            .add(SliderSetting("Halo Animation Speed", 0.0f, 2.0f, AetherConfig.HALO_SPEED::get) { value -> AetherConfig.HALO_SPEED.set(value); AetherConfig.save() }.withDecimals(1)))
        groups.add(SettingGroup.of("Hat", "Renders a chroma pyramid above your head", AetherConfig.HAT_ENABLED::get) { value -> AetherConfig.HAT_ENABLED.set(value); AetherConfig.save() }
            .add(ToggleSetting("Filled Sides", AetherConfig.HAT_FILLED::get) { value -> AetherConfig.HAT_FILLED.set(value); AetherConfig.save() })
            .add(ToggleSetting("Render In First Person", AetherConfig.HAT_RENDER_FIRST_PERSON::get) { value -> AetherConfig.HAT_RENDER_FIRST_PERSON.set(value); AetherConfig.save() })
            .add(SliderSetting("Pyramid Height", 0.1f, 3f, AetherConfig.HAT_HEIGHT::get) { value -> AetherConfig.HAT_HEIGHT.set(value); AetherConfig.save() }.withDecimals(1))
            .add(SliderSetting("Radius", 0.1f, 3f, AetherConfig.HAT_RADIUS::get) { value -> AetherConfig.HAT_RADIUS.set(value); AetherConfig.save() }.withDecimals(1))
            .add(SliderSetting("Y Offset", 0.1f, 3f, AetherConfig.HAT_Y_OFFSET::get) { value -> AetherConfig.HAT_Y_OFFSET.set(value); AetherConfig.save() }.withDecimals(1))
            .add(SliderSetting("Vertices", 3.0f, 30.0f, { AetherConfig.HAT_VERTICES.get().toFloat() }) { value -> AetherConfig.HAT_VERTICES.set(Math.round(value).toInt()); AetherConfig.save() }.withDecimals(0)))
        groups.add(SettingGroup.of("Funny Dynamic Rest", "Uses a Hypixel-style ban screen during dynamic rest", AetherConfig.FUNNY_DYNAMIC_REST::get) { value -> AetherConfig.FUNNY_DYNAMIC_REST.set(value); AetherConfig.save() })
        return MainGUIRegistry.subTab("Fun", "Cosmetic world effects", groups.toList())
    }
}
