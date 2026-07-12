package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.ListSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;

import java.util.List;

public final class VisitorRegistryProvider extends AbstractModulesRegistryProvider {
    public VisitorRegistryProvider() {
        super(4);
    }

    @Override
    protected ModulesTab.SubTab createSubTab() {
        SettingGroup group = SettingGroup.of(
                "Auto Visitor Settings",
                "Automatically fulfills visitors requests",
                () -> AetherConfig.AUTO_VISITOR.get(),
                v -> {
                    AetherConfig.AUTO_VISITOR.set(v);
                    AetherConfig.save();
                });
        group.add(new SliderSetting("Visitor Threshold", 1, 5,
                () -> (float) AetherConfig.VISITOR_THRESHOLD.get(),
                v -> {
                    AetherConfig.VISITOR_THRESHOLD.set(Math.round(v));
                    AetherConfig.save();
                })
                .withDecimals(0));
        group.add(new SliderSetting("Max Visitor Purchase", 0.0f, 20.0f,
                () -> AetherConfig.VISITOR_MAX_PURCHASE_LIMIT.get() / 1_000_000.0f,
                v -> {
                    AetherConfig.VISITOR_MAX_PURCHASE_LIMIT.set(Math.round(v * 1_000_000.0f));
                    AetherConfig.save();
                })
                .withDecimals(1).withSuffix("m"));
        group.add(new ListSetting("Visitor Ignored", "Add visitor name",
                () -> AetherConfig.VISITOR_ignore.get(),
                v -> {
                    AetherConfig.VISITOR_ignore.set(v);
                    AetherConfig.save();
                }));
        group.add(new ListSetting("Visitor Reject", "Add visitor name",
                () -> AetherConfig.VISITOR_REJECT.get(),
                v -> {
                    AetherConfig.VISITOR_REJECT.set(v);
                    AetherConfig.save();
                }));
        group.add(new ToggleSetting("Equip Custom Item",
                () -> AetherConfig.EQUIP_VISITOR_CUSTOM_ITEM.get(),
                v -> {
                    AetherConfig.EQUIP_VISITOR_CUSTOM_ITEM.set(v);
                    AetherConfig.save();
                }));
        group.add(new TextSetting("Custom Item", "e.g. Blessed Melon Dicer",
                () -> AetherConfig.VISITOR_CUSTOM_ITEM.get(),
                v -> {
                    AetherConfig.VISITOR_CUSTOM_ITEM.set(v);
                    AetherConfig.save();
                })
                .visibleWhen(() -> AetherConfig.EQUIP_VISITOR_CUSTOM_ITEM.get()));
        group.add(new ToggleSetting("Disable Compactors during Visitors",
                () -> AetherConfig.DISABLE_COMPACTORS_DURING_VISITORS.get(),
                v -> {
                    AetherConfig.DISABLE_COMPACTORS_DURING_VISITORS.set(v);
                    AetherConfig.save();
                }));
        group.add(new ToggleSetting("Disable during Jacob's Contests",
                () -> AetherConfig.DISABLE_VISITORS_DURING_JACOBS_CONTEST.get(),
                v -> {
                    AetherConfig.DISABLE_VISITORS_DURING_JACOBS_CONTEST.set(v);
                    AetherConfig.save();
                }));
        group.add(FarmingSettingsFactory.visitorFovRangeSetting());
        group.add(FarmingSettingsFactory.visitorDelaySetting());
        return MainGUIRegistry.toggleSubTab(
                "Auto Visitor",
                "Automatically interacts with visitors and fulfills their requests",
                () -> AetherConfig.AUTO_VISITOR.get(),
                v -> {
                    AetherConfig.AUTO_VISITOR.set(v);
                    AetherConfig.save();
                },
                List.of(group));
    }
}
