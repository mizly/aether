package dev.aether

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Aether : ModInitializer {
    override fun onInitialize() {
        LOGGER.info("Aether v1 Initialized!")
    }

    companion object {
        @JvmField
        val LOGGER: Logger = LoggerFactory.getLogger("aether")
    }
}
