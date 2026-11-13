package dev.aether.ui.providers.modules

import dev.aether.config.AetherConfig
import dev.aether.ui.settings.RangeSliderSetting
import dev.aether.ui.settings.SliderSetting
import dev.aether.ui.settings.ToggleSetting


internal object FarmingSettingsFactory {
    @JvmStatic
    fun sprayMaterials(): List<String> = listOf("Use Selected", "Compost", "Honey Jar", "Dung", "Plant Matter", "Tasty Cheese", "Jelly")

    private fun intDelayRangeSetting(
        name: String,
        minBound: Float,
        maxBound: Float,
        minGetter: () -> Int,
        maxGetter: () -> Int,
        setter: (Int, Int) -> Unit
    ): RangeSliderSetting = RangeSliderSetting(name, minBound, maxBound,
        { minGetter().toFloat() },
        { maxGetter().toFloat() },
        { lower, upper ->
            setter(Math.round(lower), Math.round(upper))
            AetherConfig.save()
        }).withDecimals(0).withSuffix("ms")

    @JvmStatic fun laneSwitchDelaySetting() = intDelayRangeSetting("Lane Switch Delay", 0f, 5000f,
        { AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get() }, { AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get() },
        { min, max -> AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.set(min); AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.set(max) })
    @JvmStatic fun rewarpDelaySetting() = intDelayRangeSetting("Rewarp Delay", 0f, 1000f,
        { AetherConfig.REWARP_DELAY_MIN.get() }, { AetherConfig.REWARP_DELAY_MAX.get() },
        { min, max -> AetherConfig.REWARP_DELAY_MIN.set(min); AetherConfig.REWARP_DELAY_MAX.set(max) })
    @JvmStatic fun pestDestroyerTriggerDelaySetting() = intDelayRangeSetting("Pest Destroyer Trigger Delay", 0f, 5000f,
        { AetherConfig.PEST_CHAT_TRIGGER_DELAY_MIN.get() }, { AetherConfig.PEST_CHAT_TRIGGER_DELAY_MAX.get() },
        { min, max -> AetherConfig.PEST_CHAT_TRIGGER_DELAY_MIN.set(min); AetherConfig.PEST_CHAT_TRIGGER_DELAY_MAX.set(max) })
    @JvmStatic fun ballsackShredderTriggerDelaySetting() = intDelayRangeSetting("Ballsack Shredder Trigger Delay", 0f, 30000f,
        { AetherConfig.BALLSACK_SHREDDER_TRIGGER_DELAY_MIN.get() }, { AetherConfig.BALLSACK_SHREDDER_TRIGGER_DELAY_MAX.get() },
        { min, max -> AetherConfig.BALLSACK_SHREDDER_TRIGGER_DELAY_MIN.set(min); AetherConfig.BALLSACK_SHREDDER_TRIGGER_DELAY_MAX.set(max) })
    @JvmStatic fun pestExchangeDelaySetting() = intDelayRangeSetting("Pest Exchange Delay", 0f, 5000f,
        { AetherConfig.PEST_EXCHANGE_DELAY_MIN.get() }, { AetherConfig.PEST_EXCHANGE_DELAY_MAX.get() },
        { min, max -> AetherConfig.PEST_EXCHANGE_DELAY_MIN.set(min); AetherConfig.PEST_EXCHANGE_DELAY_MAX.set(max) })
    @JvmStatic fun aotvBetweenPestsDelaySetting() = intDelayRangeSetting("AOTV Between Pests Delay", 100f, 250f,
        { AetherConfig.PEST_AOTV_DELAY_MIN.get() }, { AetherConfig.PEST_AOTV_DELAY_MAX.get() },
        { min, max -> AetherConfig.PEST_AOTV_DELAY_MIN.set(min); AetherConfig.PEST_AOTV_DELAY_MAX.set(max) })

    @JvmStatic fun pestAotvStartDistanceSetting() = SliderSetting("AOTV Start Distance (Blocks)", 12.0f, 40.0f,
        { AetherConfig.PEST_AOTV_START_DISTANCE.get() }, { v -> AetherConfig.PEST_AOTV_START_DISTANCE.set(v); AetherConfig.save() }).withDecimals(1)
    @JvmStatic fun pestAotvStopDistanceSetting() = SliderSetting("AOTV Stop Distance (Blocks)", 6.0f, 20.0f,
        { AetherConfig.PEST_AOTV_STOP_DISTANCE.get() }, { v -> AetherConfig.PEST_AOTV_STOP_DISTANCE.set(v); AetherConfig.save() }).withDecimals(1)
    @JvmStatic fun pestEtherwarpMinDistanceSetting() = SliderSetting("Etherwarp Minimum Distance (Blocks)", 10.0f, 50.0f,
        { AetherConfig.PEST_ETHERWARP_MIN_DISTANCE.get() }, { v -> AetherConfig.PEST_ETHERWARP_MIN_DISTANCE.set(v); AetherConfig.save() }).withDecimals(1)
    @JvmStatic fun rodSwapDelaySetting() = intDelayRangeSetting("Rod Swap Delay", 0f, 1000f,
        { AetherConfig.ROD_SWAP_DELAY_MIN.get() }, { AetherConfig.ROD_SWAP_DELAY_MAX.get() },
        { min, max -> AetherConfig.ROD_SWAP_DELAY_MIN.set(min); AetherConfig.ROD_SWAP_DELAY_MAX.set(max) })
    @JvmStatic fun guiFirstClickDelaySetting() = intDelayRangeSetting("GUI First Click Delay", 0f, 1000f,
        { AetherConfig.GUI_FIRST_CLICK_DELAY_MIN.get() }, { AetherConfig.GUI_FIRST_CLICK_DELAY_MAX.get() },
        { min, max -> AetherConfig.GUI_FIRST_CLICK_DELAY_MIN.set(min); AetherConfig.GUI_FIRST_CLICK_DELAY_MAX.set(max) })
    @JvmStatic fun guiClickDelaySetting() = intDelayRangeSetting("Gear Swap GUI Delay", 0f, 1000f,
        { AetherConfig.GUI_CLICK_DELAY_MIN.get() }, { AetherConfig.GUI_CLICK_DELAY_MAX.get() },
        { min, max -> AetherConfig.GUI_CLICK_DELAY_MIN.set(min); AetherConfig.GUI_CLICK_DELAY_MAX.set(max) })
    @JvmStatic fun pickUpStashDelaySetting() = intDelayRangeSetting("Pick Up Stash Delay", 0f, 5000f,
        { AetherConfig.PICK_UP_STASH_DELAY_MIN.get() }, { AetherConfig.PICK_UP_STASH_DELAY_MAX.get() },
        { min, max -> AetherConfig.PICK_UP_STASH_DELAY_MIN.set(min); AetherConfig.PICK_UP_STASH_DELAY_MAX.set(max) })
    @JvmStatic fun junkDropDelaySetting() = intDelayRangeSetting("Junk Drop Delay", 0f, 1000f,
        { AetherConfig.JUNK_ITEM_DROP_DELAY_MIN.get() }, { AetherConfig.JUNK_ITEM_DROP_DELAY_MAX.get() },
        { min, max -> AetherConfig.JUNK_ITEM_DROP_DELAY_MIN.set(min); AetherConfig.JUNK_ITEM_DROP_DELAY_MAX.set(max) })
    @JvmStatic fun georgePostSellDelaySetting() = intDelayRangeSetting("George Sell Delay Between Pets", 0f, 5000f,
        { AetherConfig.GEORGE_POST_SELL_DELAY_MIN_MS.get() }, { AetherConfig.GEORGE_POST_SELL_DELAY_MAX_MS.get() },
        { min, max -> AetherConfig.GEORGE_POST_SELL_DELAY_MIN_MS.set(min); AetherConfig.GEORGE_POST_SELL_DELAY_MAX_MS.set(max) })
    @JvmStatic fun farmWhileCallingGeorgeSetting() = ToggleSetting("Farm while calling George",
        { AetherConfig.FARM_WHILE_CALLING_GEORGE.get() }, { v -> AetherConfig.FARM_WHILE_CALLING_GEORGE.set(v); AetherConfig.save() })
    @JvmStatic fun bazaarGuiDelaySetting() = intDelayRangeSetting("Bazaar GUI Delay", 0f, 1000f,
        { AetherConfig.BAZAAR_DELAY_MIN.get() }, { AetherConfig.BAZAAR_DELAY_MAX.get() },
        { min, max -> AetherConfig.BAZAAR_DELAY_MIN.set(min); AetherConfig.BAZAAR_DELAY_MAX.set(max) })
    @JvmStatic fun visitorDelaySetting() = intDelayRangeSetting("Visitor Delay", 0f, 1000f,
        { AetherConfig.VISITOR_DELAY_MIN.get() }, { AetherConfig.VISITOR_DELAY_MAX.get() },
        { min, max -> AetherConfig.VISITOR_DELAY_MIN.set(min); AetherConfig.VISITOR_DELAY_MAX.set(max) })

    @JvmStatic fun farmingPitchRangeSetting() = SliderSetting("Farming Pitch Range", 0.0f, 10.0f,
        { AetherConfig.MACRO_CUSTOM_PITCH_HUMANIZATION.get() }, { v -> AetherConfig.MACRO_CUSTOM_PITCH_HUMANIZATION.set(v); AetherConfig.save() }).withDecimals(1).withSuffix("°")
    @JvmStatic fun farmingYawRangeSetting() = SliderSetting("Farming Yaw Range", 0.0f, 10.0f,
        { AetherConfig.MACRO_CUSTOM_YAW_HUMANIZATION.get() }, { v -> AetherConfig.MACRO_CUSTOM_YAW_HUMANIZATION.set(v); AetherConfig.save() }).withDecimals(1).withSuffix("°")
    @JvmStatic fun bpsAverageWindowSetting() = SliderSetting("BPS Average Window", 5.0f, 60.0f,
        { AetherConfig.BPS_AVERAGE_WINDOW.get().toFloat() }, { v -> AetherConfig.BPS_AVERAGE_WINDOW.set(Math.round(v).toInt()); AetherConfig.save() }).withDecimals(0).withSuffix("s")
    @JvmStatic fun aotvToRoofPitchSetting() = RangeSliderSetting("AOTV to Roof Pitch", 0.0f, 90.0f,
        { Math.max(0, AetherConfig.AOTV_ROOF_PITCH.get() - AetherConfig.AOTV_ROOF_PITCH_HUMANIZATION.get()).toFloat() },
        { Math.min(90, AetherConfig.AOTV_ROOF_PITCH.get() + AetherConfig.AOTV_ROOF_PITCH_HUMANIZATION.get()).toFloat() },
        { lower, upper ->
            val min = Math.round(lower); val max = Math.round(upper)
            AetherConfig.AOTV_ROOF_PITCH.set((min + max) / 2)
            AetherConfig.AOTV_ROOF_PITCH_HUMANIZATION.set((max - min) / 2)
            AetherConfig.save()
        }).withDecimals(0).withSuffix("°")
    @JvmStatic fun pestFovRangeSetting() = SliderSetting("Pest FOV Range", 0.0f, 90.0f,
        { AetherConfig.PEST_FOV_RANGE.get() }, { v -> AetherConfig.PEST_FOV_RANGE.set(v); AetherConfig.save() }).withDecimals(1).withSuffix("°")
    @JvmStatic fun pestAimDriftSetting() = SliderSetting("Pest Aim Drift", 0.0f, 2.0f,
        { AetherConfig.PEST_AIM_DRIFT.get() }, { v -> AetherConfig.PEST_AIM_DRIFT.set(v); AetherConfig.save() }).withDecimals(2)
    @JvmStatic fun pestMaxTurnSpeedSetting() = SliderSetting("Pest Turn Speed Limit", 60.0f, 1200.0f,
        { AetherConfig.PEST_MAX_TURN_SPEED.get() }, { v -> AetherConfig.PEST_MAX_TURN_SPEED.set(v); AetherConfig.save() }).withDecimals(0).withSuffix("°/s")
    @JvmStatic fun pestNextTargetTurnSpeedSetting() = SliderSetting("Next Pest Turn Speed", 60.0f, 1200.0f,
        { AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get() }, { v -> AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.set(v); AetherConfig.save() }).withDecimals(0).withSuffix("°/s")
    @JvmStatic fun visitorFovRangeSetting() = SliderSetting("Visitor FOV Range", 0.0f, 30.0f,
        { AetherConfig.VISITOR_FOV_RANGE.get() }, { v -> AetherConfig.VISITOR_FOV_RANGE.set(v); AetherConfig.save() }).withDecimals(1).withSuffix("°")
    @JvmStatic fun pestExchangeFovRangeSetting() = SliderSetting("Phillip FOV Range", 0.0f, 15.0f,
        { AetherConfig.PEST_EXCHANGE_FOV_RANGE.get() }, { v -> AetherConfig.PEST_EXCHANGE_FOV_RANGE.set(v); AetherConfig.save() }).withDecimals(1).withSuffix("°")
    @JvmStatic fun pestAboveAimPitchRangeSetting() = RangeSliderSetting("Pest Above Aim Pitch", 10.0f, 90.0f,
        { AetherConfig.PEST_ABOVE_TARGET_PITCH_MIN.get() }, { AetherConfig.PEST_ABOVE_TARGET_PITCH_MAX.get() },
        { lower, upper -> AetherConfig.PEST_ABOVE_TARGET_PITCH_MIN.set(lower); AetherConfig.PEST_ABOVE_TARGET_PITCH_MAX.set(upper); AetherConfig.save() }).withDecimals(0).withSuffix("°")
}
