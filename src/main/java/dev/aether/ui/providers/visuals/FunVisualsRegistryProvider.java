package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.ArrayList;
import java.util.List;

public final class FunVisualsRegistryProvider extends AbstractVisualsRegistryProvider {
    public FunVisualsRegistryProvider() {
        super(3);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        List<SettingGroup> groups = new ArrayList<>();
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
