package dev.aether.macro.fishing;

import dev.aether.modules.routes.Route;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishingMacroManagerTest {

    @Test
    void chatReachesTheMacroWithoutColourCodes() {
        ChatRecorder macro = new ChatRecorder();
        FishingMacroManager.forwardChat(macro, "§c§lThere is not enough space for another §bSea Creature§c!");
        assertEquals(List.of("There is not enough space for another Sea Creature!"), macro.lines);
    }

    @Test
    void noMacroOrNoLineIsIgnored() {
        ChatRecorder macro = new ChatRecorder();
        FishingMacroManager.forwardChat(null, "anything");
        FishingMacroManager.forwardChat(macro, null);
        assertEquals(List.of(), macro.lines);
    }

    @Test
    void aRestartFromTheSawyerSpotStillWarpsToGalatea() {
        Route restart = FishingMacroManager.chooseRoute(FishingMacroKind.STRIDER, null, true, true, 0.0);

        assertEquals("galatea", restart.warp());
        assertEquals(StriderFishingMacro.FIXED_SPOT.getX(), restart.end().x());
        assertEquals("", FishingMacroManager.chooseRoute(FishingMacroKind.STRIDER, null, false, true, 0.0).warp());
    }

    @Test
    void aSelectionWithNoWaypointsFallsBackToTheSawyerSpot() {
        Route warpOnly = new Route("a", "crimson");

        assertTrue(FishingMacroManager.usesFixedSpot(FishingMacroKind.STRIDER, warpOnly));
        Route route = FishingMacroManager.chooseRoute(FishingMacroKind.STRIDER, warpOnly, true, false, 500.0);
        assertEquals("galatea", route.warp());
        assertNotNull(route.end());
    }

    @Test
    void aWalkableSelectionIsUsedAsItIs() {
        Route selected = new Route("a", "crimson");
        selected.add(new Route.Waypoint(1, 2, 3, Route.LegType.WALK));

        assertSame(selected, FishingMacroManager.chooseRoute(FishingMacroKind.STRIDER, selected, true, true, 0.0));
    }

    @Test
    void aRestartNeedsARouteWithAWarp() {
        Route noWarp = new Route("a", "");
        noWarp.add(new Route.Waypoint(1, 2, 3, Route.LegType.WALK));

        assertNotNull(FishingMacroManager.blockedReason(null));
        assertNotNull(FishingMacroManager.blockedReason(noWarp));
        assertNull(FishingMacroManager.blockedReason(
                FishingMacroManager.chooseRoute(FishingMacroKind.STRIDER, null, true, true, 0.0)));
    }

    private static final class ChatRecorder extends AbstractFishingMacro {
        final List<String> lines = new ArrayList<>();

        @Override
        void onChat(String plain) {
            lines.add(plain);
        }

        @Override
        public void releaseAll(Minecraft mc) {
        }

        @Override
        public void onEnable(Minecraft mc) {
        }

        @Override
        public void onDisable(Minecraft mc) {
        }

        @Override
        public void onTick(Minecraft mc) {
        }
    }
}
