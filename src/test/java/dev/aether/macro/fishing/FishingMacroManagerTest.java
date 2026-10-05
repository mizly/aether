package dev.aether.macro.fishing;

import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
