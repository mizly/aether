package dev.aether.bootstrap;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class AetherKeybindRegistry {
    private static KeyMapping macroToggleKey;
    private static KeyMapping clickGuiKey;
    private static KeyMapping nvgDemoKey;
    private static KeyMapping freecamKey;
    private static KeyMapping freecamTeleportToPlayerKey;
    private static KeyMapping freelookKey;
    private static KeyMapping pipKey;
    private static KeyMapping ungrabMouseKey;
    private static KeyMapping experimentClickKey;
    private static boolean registered;

    private AetherKeybindRegistry() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        Identifier categoryId = Identifier.fromNamespaceAndPath("aether", "main");
        KeyMapping.Category category = new KeyMapping.Category(categoryId);
        try {
            macroToggleKey = KeyMappingHelper
                    .registerKeyMapping(new KeyMapping("key.aether.start_script", GLFW.GLFW_KEY_K, category));
            clickGuiKey = KeyMappingHelper
                    .registerKeyMapping(new KeyMapping("key.aether.clickgui", GLFW.GLFW_KEY_INSERT, category));
            nvgDemoKey = KeyMappingHelper
                    .registerKeyMapping(new KeyMapping("Open NVG Demo", GLFW.GLFW_KEY_HOME, category));
            freecamKey = KeyMappingHelper
                    .registerKeyMapping(new KeyMapping("key.aether.freecam", GLFW.GLFW_KEY_F6, category));
            freecamTeleportToPlayerKey = KeyMappingHelper
                    .registerKeyMapping(new KeyMapping("key.aether.freecam_teleport_to_player", GLFW.GLFW_KEY_F7, category));
            freelookKey = KeyMappingHelper
                    .registerKeyMapping(new KeyMapping("key.aether.freelook", GLFW.GLFW_KEY_LEFT_ALT, category));
            pipKey = KeyMappingHelper
                    .registerKeyMapping(new KeyMapping("key.aether.pip", GLFW.GLFW_KEY_P, category));
            ungrabMouseKey = KeyMappingHelper
                    .registerKeyMapping(new KeyMapping("key.aether.ungrab_mouse", GLFW.GLFW_KEY_U, category));
            experimentClickKey = KeyMappingHelper
                    .registerKeyMapping(new KeyMapping("key.aether.experiment_click", GLFW.GLFW_KEY_BACKSLASH, category));
        } catch (IllegalStateException ex) {
            // External feature jars can initialize after options are already built; reuse existing mappings if present.
            macroToggleKey = resolveExistingOrDetached("key.aether.start_script", GLFW.GLFW_KEY_K, category);
            clickGuiKey = resolveExistingOrDetached("key.aether.clickgui", GLFW.GLFW_KEY_INSERT, category);
            nvgDemoKey = resolveExistingOrDetached("Open NVG Demo", GLFW.GLFW_KEY_HOME, category);
            freecamKey = resolveExistingOrDetached("key.aether.freecam", GLFW.GLFW_KEY_F6, category);
            freecamTeleportToPlayerKey = resolveExistingOrDetached("key.aether.freecam_teleport_to_player", GLFW.GLFW_KEY_F7, category);
            freelookKey = resolveExistingOrDetached("key.aether.freelook", GLFW.GLFW_KEY_LEFT_ALT, category);
            pipKey = resolveExistingOrDetached("key.aether.pip", GLFW.GLFW_KEY_P, category);
            ungrabMouseKey = resolveExistingOrDetached("key.aether.ungrab_mouse", GLFW.GLFW_KEY_U, category);
            experimentClickKey = resolveExistingOrDetached("key.aether.experiment_click", GLFW.GLFW_KEY_BACKSLASH, category);
        }
        registered = true;
    }

    private static KeyMapping resolveExistingOrDetached(String translationKey, int defaultKey, KeyMapping.Category category) {
        KeyMapping existing = findExistingMapping(translationKey);
        if (existing != null) {
            return existing;
        }
        return new KeyMapping(translationKey, defaultKey, category);
    }

    private static KeyMapping findExistingMapping(String translationKey) {
        KeyMapping[] existingMappings = getRegisteredMappingsFromOptions();
        if (existingMappings == null) {
            return null;
        }

        for (KeyMapping mapping : existingMappings) {
            if (mapping != null && translationKey.equals(getMappingTranslationKey(mapping))) {
                return mapping;
            }
        }
        return null;
    }

    private static KeyMapping[] getRegisteredMappingsFromOptions() {
        Minecraft client = Minecraft.getInstance();
        Object options = client.options;
        for (Field field : options.getClass().getDeclaredFields()) {
            if (!field.getType().isArray() || !KeyMapping.class.isAssignableFrom(field.getType().getComponentType())) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(options);
                if (value == null) {
                    continue;
                }

                int length = Array.getLength(value);
                KeyMapping[] mappings = new KeyMapping[length];
                for (int i = 0; i < length; i++) {
                    mappings[i] = (KeyMapping) Array.get(value, i);
                }
                return mappings;
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return null;
    }

    private static String getMappingTranslationKey(KeyMapping mapping) {
        try {
            Method getNameMethod = KeyMapping.class.getMethod("getName");
            Object name = getNameMethod.invoke(mapping);
            return name instanceof String ? (String) name : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static KeyMapping getMacroToggleKey() {
        register();
        return macroToggleKey;
    }

    public static KeyMapping getClickGuiKey() {
        register();
        return clickGuiKey;
    }

    public static KeyMapping getNvgDemoKey() {
        register();
        return nvgDemoKey;
    }

    public static KeyMapping getFreecamKey() {
        register();
        return freecamKey;
    }

    public static KeyMapping getFreecamTeleportToPlayerKey() {
        register();
        return freecamTeleportToPlayerKey;
    }

    public static KeyMapping getFreelookKey() {
        register();
        return freelookKey;
    }

    public static KeyMapping getPipKey() {
        register();
        return pipKey;
    }

    public static KeyMapping getUngrabMouseKey() {
        register();
        return ungrabMouseKey;
    }

    public static KeyMapping getExperimentClickKey() {
        register();
        return experimentClickKey;
    }

    public static List<RegisteredKeybind> getRegisteredKeybinds() {
        register();
        return List.of(
                new RegisteredKeybind("Toggle Macro", "Starts or stops the active farming macro", getMacroToggleKey()),
                new RegisteredKeybind("Open GUI", "Opens the Aether sidebar menu", getClickGuiKey()),
                new RegisteredKeybind("Open NVG Demo", "Opens the NanoVG demo screen", getNvgDemoKey()),
                new RegisteredKeybind("Toggle Freecam", "Detaches or restores the camera (requires the Freecam module on)", getFreecamKey()),
                new RegisteredKeybind("Freecam Teleport To Player", "Snaps the observer back to the real player", getFreecamTeleportToPlayerKey()),
                new RegisteredKeybind("Freelook (hold)", "Orbit the camera while the player keeps facing forward", getFreelookKey()),
                new RegisteredKeybind("Toggle PiP", "Opens or closes the picture-in-picture window", getPipKey()),
                new RegisteredKeybind("Toggle Ungrab Mouse", "Releases or restores the mouse cursor", getUngrabMouseKey()),
                new RegisteredKeybind("Experiment Click", "In a GUI: sends a pending step-mode click, or test-clicks the hovered slot", getExperimentClickKey())
        );
    }

    public record RegisteredKeybind(String name, String description, KeyMapping mapping) {
    }
}
