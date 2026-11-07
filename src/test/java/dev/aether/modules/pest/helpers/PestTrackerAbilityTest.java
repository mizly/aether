package dev.aether.modules.pest.helpers;

import net.minecraft.SharedConstants;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class PestTrackerAbilityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void acceptsOnlyTheTrackerParticleSignature() {
        assertTrue(PestTrackerAbility.matchesPacket(packet(ParticleTypes.ANGRY_VILLAGER, 1, 0, 0)));
        assertFalse(PestTrackerAbility.matchesPacket(packet(ParticleTypes.ANGRY_VILLAGER, 10, 0, 0)));
        assertFalse(PestTrackerAbility.matchesPacket(packet(ParticleTypes.ANGRY_VILLAGER, 1, 0.5f, 0)));
        assertFalse(PestTrackerAbility.matchesPacket(packet(ParticleTypes.ANGRY_VILLAGER, 1, 0, 1)));
        assertFalse(PestTrackerAbility.matchesPacket(packet(ParticleTypes.ENCHANT, 10, 0, -2)));
        assertFalse(PestTrackerAbility.matchesPacket(packet(ParticleTypes.RAIN, 1, 0, 0)));
        assertFalse(PestTrackerAbility.matchesPacket(null));
    }

    @ParameterizedTest
    @CsvSource({"1, 0, 0", "0, 1, 0", "0, 0, 1"})
    void rejectsTrackerParticlesMovingOnAnyAxis(float xSpeed, float ySpeed, float zSpeed) {
        assertFalse(PestTrackerAbility.matchesPacket(new ClientboundLevelParticlesPacket(
                ParticleTypes.ANGRY_VILLAGER, false, false, 0, 80, 0,
                0, 0, 0, xSpeed, ySpeed, zSpeed, 1,
                ClientboundLevelParticlesPacket.RandomizationType.DEFAULT)));
    }

    private static ClientboundLevelParticlesPacket packet(ParticleOptions type, int count, float spread, float speed) {
        return new ClientboundLevelParticlesPacket(type, false, false, 0, 80, 0, spread, 0, 0, speed, count);
    }
}
