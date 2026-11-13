package dev.aether.bootstrap

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import com.mojang.blaze3d.platform.InputConstants
import kotlin.collections.List
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method

class AetherKeybindRegistry private constructor() {
    companion object {
        private var macroToggleKey: KeyMapping? = null
        private var clickGuiKey: KeyMapping? = null
        private var nvgDemoKey: KeyMapping? = null
        private var freecamKey: KeyMapping? = null
        private var freecamTeleportToPlayerKey: KeyMapping? = null
        private var freelookKey: KeyMapping? = null
        private var pipKey: KeyMapping? = null
        private var ungrabMouseKey: KeyMapping? = null
        private var manualPestEarlyFinishKey: KeyMapping? = null
        private var registered = false

        @JvmStatic
        fun register() {
            if (registered) return
            val categoryId = Identifier.fromNamespaceAndPath("aether", "main")
            val category = KeyMapping.Category(categoryId)
            try {
                macroToggleKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.aether.start_script", InputConstants.KEY_K, category))
                clickGuiKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.aether.clickgui", InputConstants.KEY_INSERT, category))
                nvgDemoKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Open NVG Demo", InputConstants.KEY_HOME, category))
                freecamKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.aether.freecam", InputConstants.KEY_F6, category))
                freecamTeleportToPlayerKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.aether.freecam_teleport_to_player", InputConstants.KEY_F7, category))
                freelookKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.aether.freelook", InputConstants.KEY_LALT, category))
                pipKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.aether.pip", InputConstants.KEY_P, category))
                ungrabMouseKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.aether.ungrab_mouse", InputConstants.KEY_U, category))
                manualPestEarlyFinishKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Manual Pest Early Finish", InputConstants.UNKNOWN.getValue(), category))
            } catch (ex: IllegalStateException) {
                // External feature jars can initialize after options are already built; reuse existing mappings if present.
                macroToggleKey = resolveExistingOrDetached("key.aether.start_script", InputConstants.KEY_K, category)
                clickGuiKey = resolveExistingOrDetached("key.aether.clickgui", InputConstants.KEY_INSERT, category)
                nvgDemoKey = resolveExistingOrDetached("Open NVG Demo", InputConstants.KEY_HOME, category)
                freecamKey = resolveExistingOrDetached("key.aether.freecam", InputConstants.KEY_F6, category)
                freecamTeleportToPlayerKey = resolveExistingOrDetached("key.aether.freecam_teleport_to_player", InputConstants.KEY_F7, category)
                freelookKey = resolveExistingOrDetached("key.aether.freelook", InputConstants.KEY_LALT, category)
                pipKey = resolveExistingOrDetached("key.aether.pip", InputConstants.KEY_P, category)
                ungrabMouseKey = resolveExistingOrDetached("key.aether.ungrab_mouse", InputConstants.KEY_U, category)
                manualPestEarlyFinishKey = resolveExistingOrDetached("Manual Pest Early Finish", InputConstants.UNKNOWN.getValue(), category)
            }
            registered = true
        }

        private fun resolveExistingOrDetached(translationKey: String, defaultKey: Int, category: KeyMapping.Category): KeyMapping {
            val existing = findExistingMapping(translationKey)
            if (existing != null) return existing
            return KeyMapping(translationKey, defaultKey, category)
        }

        private fun findExistingMapping(translationKey: String): KeyMapping? {
            val existingMappings = getRegisteredMappingsFromOptions() ?: return null
            for (mapping in existingMappings) {
                if (mapping != null && translationKey == getMappingTranslationKey(mapping)) return mapping
            }
            return null
        }

        private fun getRegisteredMappingsFromOptions(): Array<KeyMapping>? {
            val client = Minecraft.getInstance()
            val options = client.options
            for (field in options.javaClass.declaredFields) {
                if (!field.type.isArray || !KeyMapping::class.java.isAssignableFrom(field.type.componentType)) continue
                try {
                    field.isAccessible = true
                    val value = field.get(options) ?: continue
                    val length = ReflectArray.getLength(value)
                    val mappings = ReflectArray.newInstance(KeyMapping::class.java, length) as Array<KeyMapping>
                    for (i in 0 until length) mappings[i] = ReflectArray.get(value, i) as KeyMapping
                    return mappings
                } catch (_: ReflectiveOperationException) {
                }
            }
            return null
        }

        private fun getMappingTranslationKey(mapping: KeyMapping): String? {
            try {
                val getNameMethod: Method = KeyMapping::class.java.getMethod("getName")
                val name = getNameMethod.invoke(mapping)
                return if (name is String) name else null
            } catch (_: ReflectiveOperationException) {
                return null
            }
        }

        @JvmStatic fun getMacroToggleKey(): KeyMapping { register(); return macroToggleKey!! }
        @JvmStatic fun getClickGuiKey(): KeyMapping { register(); return clickGuiKey!! }
        @JvmStatic fun getNvgDemoKey(): KeyMapping { register(); return nvgDemoKey!! }
        @JvmStatic fun getFreecamKey(): KeyMapping { register(); return freecamKey!! }
        @JvmStatic fun getFreecamTeleportToPlayerKey(): KeyMapping { register(); return freecamTeleportToPlayerKey!! }
        @JvmStatic fun getFreelookKey(): KeyMapping { register(); return freelookKey!! }
        @JvmStatic fun getPipKey(): KeyMapping { register(); return pipKey!! }
        @JvmStatic fun getUngrabMouseKey(): KeyMapping { register(); return ungrabMouseKey!! }
        @JvmStatic fun getManualPestEarlyFinishKey(): KeyMapping { register(); return manualPestEarlyFinishKey!! }

        @JvmStatic
        fun getRegisteredKeybinds(): List<RegisteredKeybind> {
            register()
            return listOf(
                RegisteredKeybind("Toggle Macro", "Starts or stops the active farming macro", getMacroToggleKey()),
                RegisteredKeybind("Open GUI", "Opens the Aether sidebar menu", getClickGuiKey()),
                RegisteredKeybind("Open NVG Demo", "Opens the NanoVG demo screen", getNvgDemoKey()),
                RegisteredKeybind("Toggle Freecam", "Detaches or restores the camera (requires the Freecam module on)", getFreecamKey()),
                RegisteredKeybind("Freecam Teleport To Player", "Snaps the observer back to the real player", getFreecamTeleportToPlayerKey()),
                RegisteredKeybind("Freelook (hold)", "Orbit the camera while the player keeps facing forward", getFreelookKey()),
                RegisteredKeybind("Toggle PiP", "Opens or closes the picture-in-picture window", getPipKey()),
                RegisteredKeybind("Toggle Ungrab Mouse", "Releases or restores the mouse cursor", getUngrabMouseKey()),
                RegisteredKeybind("Manual Pest Early Finish", "While manual pest cleaning is waiting, skip straight to pest post-actions and resume farming", getManualPestEarlyFinishKey())
            )
        }
    }

    class RegisteredKeybind(val name: String, val description: String, val mapping: KeyMapping) {
        override fun equals(other: Any?): Boolean = other is RegisteredKeybind && name == other.name && description == other.description && mapping == other.mapping
        override fun hashCode(): Int = java.util.Objects.hash(name, description, mapping)
        override fun toString(): String = "RegisteredKeybind[name=$name, description=$description, mapping=$mapping]"
    }
}
