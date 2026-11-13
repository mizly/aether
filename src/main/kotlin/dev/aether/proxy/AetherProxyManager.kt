package dev.aether.proxy

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dev.aether.util.AetherLang.Companion.localize
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import java.io.Reader
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.Collections

class AetherProxyManager private constructor() {
    companion object {
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private val PROXY_LIST_TYPE: Type = object : TypeToken<List<AetherProxy>>() {
        }.type
        private val CONFIG_PATH: Path = FabricLoader.getInstance().getConfigDir().resolve("aether_proxies.json")

        private val PROXIES: MutableList<AetherProxy> = ArrayList()
        private var enabled = false
        private var selectedIndex = -1
        private var lastUsedProxy: AetherProxy? = null

        @JvmStatic
        @Synchronized
        fun init() {
            load()
        }

        @JvmStatic
        @Synchronized
        fun load() {
            PROXIES.clear()
            enabled = false
            selectedIndex = -1
            lastUsedProxy = null

            if (!Files.exists(CONFIG_PATH)) {
                save()
                return
            }

            try {
                Files.newBufferedReader(CONFIG_PATH).use { reader: Reader ->
                    val root = JsonParser.parseReader(reader).asJsonObject
                    enabled = root.has("enabled") && root.get("enabled").asBoolean
                    selectedIndex = if (root.has("selectedIndex")) root.get("selectedIndex").asInt else -1
                    if (root.has("proxies") && root.get("proxies").isJsonArray) {
                        val loaded: List<AetherProxy>? = GSON.fromJson(root.get("proxies"), PROXY_LIST_TYPE)
                        if (loaded != null) {
                            for (proxy in loaded) {
                                if (proxy != null) {
                                    PROXIES.add(proxy.copy())
                                }
                            }
                        }
                    }
                    normalizeSelection()
                    refreshProxyStatusButtons()
                }
            } catch (e: Exception) {
                System.err.println("[Aether] Proxy config load failed: " + e.message)
                PROXIES.clear()
                enabled = false
                selectedIndex = -1
            }
        }

        @JvmStatic
        @Synchronized
        fun save() {
            normalizeSelection()
            val root = JsonObject()
            root.addProperty("enabled", enabled)
            root.addProperty("selectedIndex", selectedIndex)
            root.add("proxies", GSON.toJsonTree(PROXIES, PROXY_LIST_TYPE))

            try {
                val parent = CONFIG_PATH.parent
                if (parent != null) {
                    Files.createDirectories(parent)
                }
                Files.writeString(CONFIG_PATH, GSON.toJson(root))
            } catch (e: Exception) {
                System.err.println("[Aether] Proxy config save failed: " + e.message)
            }
        }

        @JvmStatic
        @Synchronized
        fun proxies(): List<AetherProxy> {
            return Collections.unmodifiableList(ArrayList(PROXIES))
        }

        @JvmStatic
        @Synchronized
        fun isEnabled(): Boolean {
            return enabled
        }

        @JvmStatic
        @Synchronized
        fun setEnabled(value: Boolean) {
            enabled = value
            save()
            refreshProxyStatusButtons()
        }

        @JvmStatic
        @Synchronized
        fun selectedIndex(): Int {
            normalizeSelection()
            return selectedIndex
        }

        @JvmStatic
        @Synchronized
        fun select(index: Int) {
            selectedIndex = index
            normalizeSelection()
            save()
            refreshProxyStatusButtons()
        }

        @JvmStatic
        @Synchronized
        fun add(proxy: AetherProxy) {
            PROXIES.add(proxy.copy())
            selectedIndex = PROXIES.size - 1
            save()
            refreshProxyStatusButtons()
        }

        @JvmStatic
        @Synchronized
        fun update(index: Int, proxy: AetherProxy) {
            if (index < 0 || index >= PROXIES.size) {
                return
            }
            PROXIES[index] = proxy.copy()
            save()
            refreshProxyStatusButtons()
        }

        @JvmStatic
        @Synchronized
        fun remove(index: Int) {
            if (index < 0 || index >= PROXIES.size) {
                return
            }
            PROXIES.removeAt(index)
            if (selectedIndex >= PROXIES.size) {
                selectedIndex = PROXIES.size - 1
            }
            save()
            refreshProxyStatusButtons()
        }

        @JvmStatic
        @Synchronized
        fun selectedProxy(): AetherProxy? {
            normalizeSelection()
            if (!enabled || selectedIndex < 0 || selectedIndex >= PROXIES.size) {
                return null
            }
            val proxy = PROXIES[selectedIndex]
            return if (proxy.isValid()) proxy.copy() else null
        }

        @JvmStatic
        @Synchronized
        fun markConnectionProxy(proxy: AetherProxy?) {
            lastUsedProxy = if (proxy == null) null else proxy.copy()
        }

        @JvmStatic
        @Synchronized
        fun selectedStatus(): String {
            val proxy = selectedProxy()
            if (!enabled) {
                return localize("Proxy: off")
            }
            if (proxy == null) {
                return localize("Proxy: invalid")
            }
            return localize("Proxy: ") + proxy.displayName()
        }

        @JvmStatic
        @Synchronized
        fun lastUsedStatus(): String {
            return if (lastUsedProxy == null) localize("none") else lastUsedProxy!!.address()
        }

        private fun normalizeSelection() {
            if (PROXIES.isEmpty()) {
                selectedIndex = -1
                enabled = false
            } else if (selectedIndex < 0 || selectedIndex >= PROXIES.size) {
                selectedIndex = 0
            }
        }

        @JvmStatic
        fun refreshProxyStatusButtons() {
            val minecraft = Minecraft.getInstance()
            if (minecraft == null) {
                return
            }
            refreshProxyStatusButtons(minecraft.gui.screen())
        }

        @JvmStatic
        fun refreshProxyStatusButtons(screen: Screen?) {
            if (screen == null) {
                return
            }
            if (screen !is TitleScreen && screen !is JoinMultiplayerScreen) {
                return
            }

            val status = selectedStatus()
            var candidate: Button? = null
            for (listener in screen.children()) {
                if (listener is Button && isProxyStatusButton(screen, listener)) {
                    candidate = listener
                }
            }
            if (candidate != null) {
                candidate.setMessage(Component.literal(status))
            }
        }

        private fun isProxyStatusButton(screen: Screen, button: Button): Boolean {
            val guiWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth()
            val guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight()
            return button != null
                    && ((screen is TitleScreen && button.getWidth() == 200
                    && button.getHeight() == 20
                    && button.getX() == guiWidth / 2 - 100)
                    || (screen is JoinMultiplayerScreen && button.getWidth() == 180
                    && button.getHeight() == 20
                    && button.getY() == 6
                    && button.getX() == guiWidth - 185))
        }
    }
}
