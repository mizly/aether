package dev.aether.ui.settings

import com.mojang.blaze3d.platform.InputConstants
import dev.aether.util.AetherLang
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import java.util.function.Supplier

class KeybindSetting(name: String, private val keyMapping: KeyMapping, private val description: String = "") : Setting {
    private val name: String = AetherLang.localize(name)
    private val rawName: String = name
    private var visibility: Supplier<Boolean> = Supplier { true }
    fun getKeyMapping(): KeyMapping = keyMapping
    fun getBoundKeyName(): String = keyMapping.translatedKeyMessage.string
    fun isDefault(): Boolean = keyMapping.isDefault
    fun setBoundKey(key: InputConstants.Key) { keyMapping.setKey(key); KeyMapping.resetMapping(); Minecraft.getInstance().options.save() }
    fun resetToDefault() = setBoundKey(keyMapping.defaultKey)
    fun clearBinding() = setBoundKey(InputConstants.UNKNOWN)
    fun visibleWhen(condition: Supplier<Boolean>): KeybindSetting { visibility = condition; return this }
    override fun getName(): String = name
    override fun getRawName(): String = rawName
    override fun getDescription(): String = description
    override fun getType(): SettingType = SettingType.KEYBIND
    override fun isVisible(): Boolean = visibility.get()
}
