package dev.aether;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Aether implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("aether");

    @Override
    public void onInitialize() {
        LOGGER.info("Aether v1 Initialized!");
    }
}
