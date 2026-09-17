package dev.aether.ui.providers.failsafes;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeAction;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager;
import dev.aether.ui.settings.DropdownSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class FailsafeActionSettings {
    private static final List<FailsafeAction> ACTIONS = List.of(
            FailsafeAction.STOP, FailsafeAction.IGNORE, FailsafeAction.CUSTOM);
    private static final List<FailsafeAction> FISHING_ACTIONS = List.of(
            FailsafeAction.STOP, FailsafeAction.IGNORE, FailsafeAction.CUSTOM, FailsafeAction.RESTART);

    private FailsafeActionSettings() {
    }

    static DropdownSetting createActionDropdown(String label, Supplier<String> getter, Consumer<String> setter) {
        return createDropdown(label, ACTIONS, getter, setter);
    }

    static DropdownSetting createFishingActionDropdown(String label, Supplier<String> getter, Consumer<String> setter) {
        return createDropdown(label, FISHING_ACTIONS, getter, setter);
    }

    private static DropdownSetting createDropdown(String label, List<FailsafeAction> actions,
                                                  Supplier<String> getter, Consumer<String> setter) {
        List<String> options = actions.stream().map(FailsafeActionSettings::optionLabel).toList();
        return new DropdownSetting(label, options,
                () -> Math.max(0, actions.indexOf(FailsafeAction.fromConfig(getter.get()))),
                index -> {
                    if (index < 0 || index >= actions.size()) {
                        return;
                    }

                    setter.accept(actions.get(index).name());
                    AetherConfig.save();
                });
    }

    private static String optionLabel(FailsafeAction action) {
        return switch (action) {
            case STOP -> "Stop";
            case IGNORE -> "Ignore";
            case CUSTOM -> "Custom";
            case RESTART -> "Restart In New Lobby";
        };
    }

    static DropdownSetting createCustomReplayDropdown(
            FailsafeCustomReplayManager.FailsafeReplayType type,
            Supplier<String> actionGetter
    ) {
        List<String> replayOptions = new ArrayList<>(FailsafeCustomReplayManager.getAvailableReplayOptions(type));
        return new DropdownSetting("Custom Replay (/aether movement or aether.cat/editor)", replayOptions,
                () -> FailsafeCustomReplayManager.getSelectedReplayIndex(type, replayOptions),
                index -> FailsafeCustomReplayManager.setSelectedReplay(type, replayOptions, index))
                .addIconAction("/assets/aether/icons/folder.svg",
                        () -> FailsafeCustomReplayManager.openReplayFolder(type))
                .addIconAction("/assets/aether/icons/refresh.svg",
                        () -> FailsafeCustomReplayManager.refreshReplayOptions(type, replayOptions))
                .visibleWhen(() -> "CUSTOM".equalsIgnoreCase(actionGetter.get()));
    }
}
