package dev.aether.ui.providers.visuals;

import dev.aether.config.AetherConfig;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.providers.base.AbstractVisualsRegistryProvider;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.modules.visuals.PestDefeatEffects;

import java.util.ArrayList;
import java.util.List;

public final class FunVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public FunVisualsRegistryProvider() {
        super(3);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();
        groups.add(SettingGroup.of("Pest Defeat Effects", "Shader bursts when a pest is defeated or caught",
                        AetherConfig.PEST_DEFEAT_EFFECTS::get,
                        value -> { AetherConfig.PEST_DEFEAT_EFFECTS.set(value); AetherConfig.save(); })
                .add(new DropdownSetting("Effect Style", List.of("Ender Rift", "Arcane Bloom", "Solar Flare"),
                        AetherConfig.PEST_DEFEAT_STYLE::get,
                        value -> { AetherConfig.PEST_DEFEAT_STYLE.set(value); AetherConfig.save(); }))
                .add(new SliderSetting("Effect Size", 0.5f, 2f, AetherConfig.PEST_DEFEAT_SCALE::get,
                        value -> { AetherConfig.PEST_DEFEAT_SCALE.set(value); AetherConfig.save(); }).withDecimals(1))
                .add(new SliderSetting("Particle Count", 8, 24, () -> (float) AetherConfig.PEST_DEFEAT_PARTICLES.get(),
                        value -> { AetherConfig.PEST_DEFEAT_PARTICLES.set(Math.round(value)); AetherConfig.save(); }).withDecimals(0))
                .add(new ActionSetting("Preview Effect", PestDefeatEffects::preview)));
        groups.add(SettingGroup.of("Dragon Wings", "Animated dragon wings on your back in third person",
                        AetherConfig.DRAGON_WINGS_ENABLED::get,
                        value -> { AetherConfig.DRAGON_WINGS_ENABLED.set(value); AetherConfig.save(); })
                .add(new ToggleSetting("Wireframe", AetherConfig.DRAGON_WINGS_WIREFRAME::get,
                        value -> { AetherConfig.DRAGON_WINGS_WIREFRAME.set(value); AetherConfig.save(); }))
                .add(new SliderSetting("Wingspan", 0.5f, 1.4f, AetherConfig.DRAGON_WINGS_SCALE::get,
                        value -> { AetherConfig.DRAGON_WINGS_SCALE.set(value); AetherConfig.save(); }).withDecimals(2))
                .add(new SliderSetting("Wing Animation Speed", 0.4f, 2f, AetherConfig.DRAGON_WINGS_SPEED::get,
                        value -> { AetherConfig.DRAGON_WINGS_SPEED.set(value); AetherConfig.save(); }).withDecimals(1))
                .add(new ColorSetting("Wing Accent", AetherConfig.DRAGON_WINGS_COLOR::get,
                        value -> { AetherConfig.DRAGON_WINGS_COLOR.set(value); AetherConfig.save(); }))
                .add(new ToggleSetting("Membrane Glow", AetherConfig.DRAGON_WINGS_GLOW::get,
                        value -> { AetherConfig.DRAGON_WINGS_GLOW.set(value); AetherConfig.save(); })
                        .visibleWhen(() -> !AetherConfig.DRAGON_WINGS_WIREFRAME.get())));
        groups.add(SettingGroup.of(
                        "Hat",
                        "Renders a chroma pyramid above your head",
                        () -> AetherConfig.HAT_ENABLED.get(),
                        v -> {
                            AetherConfig.HAT_ENABLED.set(v);
                            AetherConfig.save();
                        })
                .add(new ToggleSetting("Filled Sides",
                        () -> AetherConfig.HAT_FILLED.get(),
                        v -> {
                            AetherConfig.HAT_FILLED.set(v);
                            AetherConfig.save();
                        }))
                .add(new ToggleSetting("Render In First Person",
                        () -> AetherConfig.HAT_RENDER_FIRST_PERSON.get(),
                        v -> {
                            AetherConfig.HAT_RENDER_FIRST_PERSON.set(v);
                            AetherConfig.save();
                        }))
                .add(new SliderSetting("Pyramid Height", 0.1f, 3.0f,
                        () -> AetherConfig.HAT_HEIGHT.get(),
                        v -> {
                            AetherConfig.HAT_HEIGHT.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1))
                .add(new SliderSetting("Radius", 0.1f, 3.0f,
                        () -> AetherConfig.HAT_RADIUS.get(),
                        v -> {
                            AetherConfig.HAT_RADIUS.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1))
                .add(new SliderSetting("Y Offset", 0.1f, 3.0f,
                        () -> AetherConfig.HAT_Y_OFFSET.get(),
                        v -> {
                            AetherConfig.HAT_Y_OFFSET.set(v);
                            AetherConfig.save();
                        })
                        .withDecimals(1))
                .add(new SliderSetting("Vertices", 3.0f, 30.0f,
                        () -> (float) AetherConfig.HAT_VERTICES.get(),
                        v -> {
                            AetherConfig.HAT_VERTICES.set(Math.round(v));
                            AetherConfig.save();
                        })
                        .withDecimals(0)));
        groups.add(SettingGroup.of(
                        "Funny Dynamic Rest",
                        "Uses a Hypixel-style ban screen during dynamic rest",
                        () -> AetherConfig.FUNNY_DYNAMIC_REST.get(),
                        v -> {
                            AetherConfig.FUNNY_DYNAMIC_REST.set(v);
                            AetherConfig.save();
                        }));
        return MainGUIRegistry.subTab("Fun", "Cosmetic world effects", groups);
    }
}
