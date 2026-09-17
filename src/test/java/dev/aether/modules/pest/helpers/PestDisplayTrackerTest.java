package dev.aether.modules.pest.helpers;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PestDisplayTrackerTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void matchesGroundPestSkullsWithArmorStandFeetBelowTheMob() {
        Entity mob = entity(0, 80, 0);
        for (double offset : new double[]{-2, -1.75, -1.5, -1, 0}) {
            Entity skull = entity(0, 80 + offset, 0);
            assertSame(mob, PestDisplayTracker.findSkullOwner(skull, null, List.of(mob), List.of(mob)));
        }
    }

    @Test
    void keepsConfirmedOwnerWhenMovementUpdatesTemporarilySeparateEntities() {
        Entity mob = entity(0, 80, 0);
        Entity skull = entity(0, 78.5, 0);
        Entity neighbour = entity(0.25, 80, 0);
        List<Entity> mobs = List.of(mob, neighbour);
        Entity owner = PestDisplayTracker.findSkullOwner(skull, null, mobs, mobs);
        assertSame(mob, owner);

        mob.setPos(4, 83, 0);
        assertSame(mob, PestDisplayTracker.findSkullOwner(skull, owner.getId(), mobs, mobs));
        skull.setPos(4, 81.5, 0);
        assertSame(mob, PestDisplayTracker.findSkullOwner(skull, owner.getId(), mobs, mobs));
    }

    @Test
    void doesNotGiveADeadPestsLingeringSkullToItsNeighbour() {
        Entity mob = entity(0, 80, 0);
        Entity neighbour = entity(0.25, 80, 0);
        Entity skull = entity(0, 78.5, 0);
        assertNull(PestDisplayTracker.findSkullOwner(skull, mob.getId(), List.of(neighbour), List.of(neighbour)));
        assertNull(PestDisplayTracker.findSkullOwner(skull, mob.getId(), List.of(), List.of()));
    }

    @Test
    void followsPassengerChainBeforeUsingNearbyMobs() {
        Entity mob = entity(4, 80, 0);
        Entity neighbour = entity(0, 80, 0);
        TestEntity carrier = entity(0, 80, 0);
        TestEntity skull = entity(0, 78.5, 0);
        skull.vehicle = carrier;
        carrier.vehicle = mob;
        assertSame(mob, PestDisplayTracker.findSkullOwner(skull, null, List.of(mob, neighbour), List.of(neighbour)));
    }

    @Test
    void prefersThePestOverALassoHelperAndFallsBackWhenNecessary() {
        Entity helper = entity(0, 80, 0);
        Entity mob = entity(0.5, 80, 0);
        Entity skull = entity(0, 78.5, 0);
        assertSame(mob, PestDisplayTracker.findSkullOwner(skull, null, List.of(helper, mob), List.of(mob)));
        assertSame(helper, PestDisplayTracker.findSkullOwner(skull, null, List.of(helper), List.of()));
    }

    @Test
    void doesNotAssociateUnconfirmedSkullsWithDistantPests() {
        Entity mob = entity(0, 80, 0);
        for (Entity skull : List.of(entity(1.6, 80, 0), entity(0, 77.9, 0), entity(0, 85.1, 0))) {
            assertNull(PestDisplayTracker.findSkullOwner(skull, null, List.of(mob), List.of(mob)));
        }
    }

    private static TestEntity entity(double x, double y, double z) {
        TestEntity entity = new TestEntity();
        entity.setPos(x, y, z);
        return entity;
    }

    private static final class TestEntity extends Interaction {
        private Entity vehicle;

        private TestEntity() {
            super(EntityType.INTERACTION, null);
        }

        @Override
        public Entity getVehicle() {
            return vehicle;
        }
    }
}
