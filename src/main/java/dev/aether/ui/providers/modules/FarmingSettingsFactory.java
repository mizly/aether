package dev.aether.ui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.settings.RangeSliderSetting;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.ToggleSetting;

final class FarmingSettingsFactory {
    private FarmingSettingsFactory() {}

    static java.util.List<String> sprayMaterials() {
        // Include an explicit "Use selected" option as the first choice so users can
        // opt to let the sprayonator use whatever material is currently selected.
        return java.util.List.of("Use Selected", "Compost", "Honey Jar", "Dung", "Plant Matter",
                "Tasty Cheese", "Jelly");
    }

    private static RangeSliderSetting intDelayRangeSetting(String name,
                                                           float minBound,
                                                           float maxBound,
                                                           java.util.function.Supplier<Integer> minGetter,
                                                           java.util.function.Supplier<Integer> maxGetter,
                                                           java.util.function.BiConsumer<Integer, Integer> setter) {
        return new RangeSliderSetting(name, minBound, maxBound,
                () -> minGetter.get().floatValue(),
                () -> maxGetter.get().floatValue(),
                (lower, upper) -> {
                    setter.accept(Math.round(lower), Math.round(upper));
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix("ms");
    }

    static RangeSliderSetting laneSwitchDelaySetting() {
        return intDelayRangeSetting("Lane Switch Delay", 0f, 1000f,
                () -> AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(),
                () -> AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.set(min);
                    AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting rewarpDelaySetting() {
        return intDelayRangeSetting("Rewarp Delay", 0f, 1000f,
                () -> AetherConfig.REWARP_DELAY_MIN.get(),
                () -> AetherConfig.REWARP_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.REWARP_DELAY_MIN.set(min);
                    AetherConfig.REWARP_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting pestDestroyerTriggerDelaySetting() {
        return intDelayRangeSetting("Pest Destroyer Trigger Delay", 0f, 5000f,
                () -> AetherConfig.PEST_CHAT_TRIGGER_DELAY_MIN.get(),
                () -> AetherConfig.PEST_CHAT_TRIGGER_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.PEST_CHAT_TRIGGER_DELAY_MIN.set(min);
                    AetherConfig.PEST_CHAT_TRIGGER_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting pestExchangeDelaySetting() {
        return intDelayRangeSetting("Pest Exchange Delay", 0f, 5000f,
                () -> AetherConfig.PEST_EXCHANGE_DELAY_MIN.get(),
                () -> AetherConfig.PEST_EXCHANGE_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.PEST_EXCHANGE_DELAY_MIN.set(min);
                    AetherConfig.PEST_EXCHANGE_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting aotvBetweenPestsDelaySetting() {
        return intDelayRangeSetting("AOTV Between Pests Delay", 100f, 250f,
                () -> AetherConfig.PEST_AOTV_DELAY_MIN.get(),
                () -> AetherConfig.PEST_AOTV_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.PEST_AOTV_DELAY_MIN.set(min);
                    AetherConfig.PEST_AOTV_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting rodSwapDelaySetting() {
        return intDelayRangeSetting("Rod Swap Delay", 0f, 1000f,
                () -> AetherConfig.ROD_SWAP_DELAY_MIN.get(),
                () -> AetherConfig.ROD_SWAP_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.ROD_SWAP_DELAY_MIN.set(min);
                    AetherConfig.ROD_SWAP_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting guiFirstClickDelaySetting() {
        return intDelayRangeSetting("GUI First Click Delay", 0f, 1000f,
                () -> AetherConfig.GUI_FIRST_CLICK_DELAY_MIN.get(),
                () -> AetherConfig.GUI_FIRST_CLICK_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.GUI_FIRST_CLICK_DELAY_MIN.set(min);
                    AetherConfig.GUI_FIRST_CLICK_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting guiClickDelaySetting() {
        return intDelayRangeSetting("Gear Swap GUI Delay", 0f, 1000f,
                () -> AetherConfig.GUI_CLICK_DELAY_MIN.get(),
                () -> AetherConfig.GUI_CLICK_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.GUI_CLICK_DELAY_MIN.set(min);
                    AetherConfig.GUI_CLICK_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting pickUpStashDelaySetting() {
        return intDelayRangeSetting("Pick Up Stash Delay", 0f, 5000f,
                () -> AetherConfig.PICK_UP_STASH_DELAY_MIN.get(),
                () -> AetherConfig.PICK_UP_STASH_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.PICK_UP_STASH_DELAY_MIN.set(min);
                    AetherConfig.PICK_UP_STASH_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting junkDropDelaySetting() {
        return intDelayRangeSetting("Junk Drop Delay", 0f, 1000f,
                () -> AetherConfig.JUNK_ITEM_DROP_DELAY_MIN.get(),
                () -> AetherConfig.JUNK_ITEM_DROP_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.JUNK_ITEM_DROP_DELAY_MIN.set(min);
                    AetherConfig.JUNK_ITEM_DROP_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting georgePostSellDelaySetting() {
        return intDelayRangeSetting("George Sell Delay Between Pets", 0f, 5000f,
                () -> AetherConfig.GEORGE_POST_SELL_DELAY_MIN_MS.get(),
                () -> AetherConfig.GEORGE_POST_SELL_DELAY_MAX_MS.get(),
                (min, max) -> {
                    AetherConfig.GEORGE_POST_SELL_DELAY_MIN_MS.set(min);
                    AetherConfig.GEORGE_POST_SELL_DELAY_MAX_MS.set(max);
                });
    }

    static ToggleSetting farmWhileCallingGeorgeSetting() {
        return new ToggleSetting("Farm while calling George",
                () -> AetherConfig.FARM_WHILE_CALLING_GEORGE.get(),
                v -> {
                    AetherConfig.FARM_WHILE_CALLING_GEORGE.set(v);
                    AetherConfig.save();
                });
    }

    static RangeSliderSetting bazaarGuiDelaySetting() {
        return intDelayRangeSetting("Bazaar GUI Delay", 0f, 1000f,
                () -> AetherConfig.BAZAAR_DELAY_MIN.get(),
                () -> AetherConfig.BAZAAR_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.BAZAAR_DELAY_MIN.set(min);
                    AetherConfig.BAZAAR_DELAY_MAX.set(max);
                });
    }

    static RangeSliderSetting visitorDelaySetting() {
        return intDelayRangeSetting("Visitor Delay", 0f, 1000f,
                () -> AetherConfig.VISITOR_DELAY_MIN.get(),
                () -> AetherConfig.VISITOR_DELAY_MAX.get(),
                (min, max) -> {
                    AetherConfig.VISITOR_DELAY_MIN.set(min);
                    AetherConfig.VISITOR_DELAY_MAX.set(max);
                });
    }

    static SliderSetting farmingPitchRangeSetting() {
        return new SliderSetting("Farming Pitch Range", 0, 10,
                () -> AetherConfig.MACRO_CUSTOM_PITCH_HUMANIZATION.get(),
                v -> {
                    AetherConfig.MACRO_CUSTOM_PITCH_HUMANIZATION.set(v);
                    AetherConfig.save();
                })
                .withDecimals(1).withSuffix("\u00B0");
    }

    static SliderSetting farmingYawRangeSetting() {
        return new SliderSetting("Farming Yaw Range", 0, 10,
                () -> AetherConfig.MACRO_CUSTOM_YAW_HUMANIZATION.get(),
                v -> {
                    AetherConfig.MACRO_CUSTOM_YAW_HUMANIZATION.set(v);
                    AetherConfig.save();
                })
                .withDecimals(1).withSuffix("\u00B0");
    }

    static SliderSetting bpsAverageWindowSetting() {
        return new SliderSetting("BPS Average Window", 5, 60,
                () -> (float) AetherConfig.BPS_AVERAGE_WINDOW.get(),
                v -> {
                    AetherConfig.BPS_AVERAGE_WINDOW.set(Math.round(v));
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix("s");
    }

    static SliderSetting aotvToRoofPitchRangeSetting() {
        return new SliderSetting("AOTV to Roof Pitch Range", 0, 15,
                () -> (float) AetherConfig.AOTV_ROOF_PITCH_HUMANIZATION.get(),
                v -> {
                    AetherConfig.AOTV_ROOF_PITCH_HUMANIZATION.set(Math.round(v));
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix("\u00B0");
    }

    static SliderSetting pestFovRangeSetting() {
        return new SliderSetting("Pest FOV Range", 0, 90,
                () -> AetherConfig.PEST_FOV_RANGE.get(),
                v -> {
                    AetherConfig.PEST_FOV_RANGE.set(v);
                    AetherConfig.save();
                })
                .withDecimals(1).withSuffix("\u00B0");
    }

    static SliderSetting visitorFovRangeSetting() {
        return new SliderSetting("Visitor FOV Range", 0, 30,
                () -> AetherConfig.VISITOR_FOV_RANGE.get(),
                v -> {
                    AetherConfig.VISITOR_FOV_RANGE.set(v);
                    AetherConfig.save();
                })
                .withDecimals(1).withSuffix("\u00B0");
    }

    static SliderSetting pestExchangeFovRangeSetting() {
        return new SliderSetting("Phillip FOV Range", 0, 15,
                () -> AetherConfig.PEST_EXCHANGE_FOV_RANGE.get(),
                v -> {
                    AetherConfig.PEST_EXCHANGE_FOV_RANGE.set(v);
                    AetherConfig.save();
                })
                .withDecimals(1).withSuffix("\u00B0");
    }

    static RangeSliderSetting pestAboveAimPitchRangeSetting() {
        return new RangeSliderSetting("Pest Above Aim Pitch", 10, 90,
                () -> AetherConfig.PEST_ABOVE_TARGET_PITCH_MIN.get(),
                () -> AetherConfig.PEST_ABOVE_TARGET_PITCH_MAX.get(),
                (lower, upper) -> {
                    AetherConfig.PEST_ABOVE_TARGET_PITCH_MIN.set(lower);
                    AetherConfig.PEST_ABOVE_TARGET_PITCH_MAX.set(upper);
                    AetherConfig.save();
                })
                .withDecimals(0).withSuffix("\u00B0");
    }
}
