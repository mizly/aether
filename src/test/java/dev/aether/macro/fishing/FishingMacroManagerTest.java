package dev.aether.macro.fishing;

import dev.aether.modules.routes.Route;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void theStriderNeedsARouteButTheFishingMacroDoesNot() {
        Route selected = walkable("a", "galatea");

        assertNotNull(FishingMacroManager.blockedStart(FishingMacroKind.STRIDER, null));
        assertNull(FishingMacroManager.blockedStart(FishingMacroKind.STRIDER, selected));
        assertNull(FishingMacroManager.blockedStart(FishingMacroKind.GENERAL, null));
    }

    @Test
    void aStartOnTheWarpsIslandBeginsWhereThePlayerIs() {
        Route galatea = walkable("a", "galatea");

        assertTrue(FishingMacroManager.beginsHere(galatea, "Galatea"));
        assertFalse(FishingMacroManager.beginsHere(galatea, "Hub"));
        assertFalse(FishingMacroManager.beginsHere(galatea, null));
        assertTrue(FishingMacroManager.beginsHere(walkable("b", ""), "Hub"));
    }

    @Test
    void aRestartNeedsARouteWithAWarp() {
        assertNotNull(FishingMacroManager.blockedReason(null));
        assertNotNull(FishingMacroManager.blockedReason(walkable("a", "")));
        assertNull(FishingMacroManager.blockedReason(walkable("b", "crimson")));
    }

    private static Route walkable(String name, String warp) {
        Route route = new Route(name, warp);
        route.add(new Route.Waypoint(1, 2, 3, Route.LegType.WALK));
        return route;
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
