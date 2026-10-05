package dev.aether.util;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkyblockItemsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (!Items.PAPER.builtInRegistryHolder().areComponentsBound()) {
            Items.PAPER.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
                    .addAll(DataComponents.COMMON_ITEM_COMPONENTS)
                    .set(DataComponents.ITEM_NAME, Component.literal("Paper")).build());
        }
    }

    private static ItemStack withId(String id) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static ItemStack withLegacyId(String id) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag attributes = new CompoundTag();
        attributes.putString("id", id);
        CompoundTag tag = new CompoundTag();
        tag.put("ExtraAttributes", attributes);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static ItemStack named(String name) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    @Test
    void readsTheIdFromEitherCustomDataLayout() {
        assertEquals("HYPERION", SkyblockItems.skyblockId(withId("HYPERION")));
        assertEquals("SOUL_WHIP", SkyblockItems.skyblockId(withLegacyId("SOUL_WHIP")));
        assertEquals("", SkyblockItems.skyblockId(named("Hyperion")));
        assertEquals("", SkyblockItems.skyblockId(ItemStack.EMPTY));
        assertEquals("", SkyblockItems.skyblockId(null));
    }

    @Test
    void thePlainNameDropsColoursAndHardSpaces() {
        assertEquals("Heroic Hyperion ✪✪✪✪✪", SkyblockItems.plainName(named("§6Heroic Hyperion §d✪✪✪✪✪ ")));
        assertEquals("", SkyblockItems.plainName(ItemStack.EMPTY));
    }

    @Test
    void everyWitherImpactBladeCountsAsAHyperion() {
        for (String id : List.of("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE", "NECRON_BLADE")) {
            assertTrue(SkyblockItems.isHyperion(withId(id)), id);
            assertTrue(SkyblockItems.isHyperion(withLegacyId(id)), id);
        }
        assertTrue(SkyblockItems.isHyperion(named("§dWithered Hyperion §6✪✪✪✪✪")));
        assertFalse(SkyblockItems.isHyperion(withId("ASPECT_OF_THE_VOID")));
        assertFalse(SkyblockItems.isHyperion(ItemStack.EMPTY));
    }

    @Test
    void anIdIsNeverOverruledByTheName() {
        ItemStack renamed = withId("ASPECT_OF_THE_END");
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Hyperion"));
        assertFalse(SkyblockItems.isHyperion(renamed));
        assertTrue(SkyblockItems.isHyperion(withId("hyperion")));
    }

    @Test
    void wandsWithoutAnIdMatchTheirBaseNameInsideAReforgedName() {
        assertTrue(SkyblockItems.isHealingWand(named("§6Heroic Wand of Atonement §d✪✪")));
        assertTrue(SkyblockItems.isHealingWand(named("§9Wand of Mending")));
        assertTrue(SkyblockItems.isHealingWand(named("Wand of Healing")));
        assertTrue(SkyblockItems.isHealingWand(named("§5Wand of Restoration")));
        assertFalse(SkyblockItems.isHealingWand(named("Fire Wand")));
        for (String id : List.of("WAND_OF_HEALING", "WAND_OF_MENDING", "WAND_OF_RESTORATION", "WAND_OF_ATONEMENT")) {
            assertTrue(SkyblockItems.isHealingWand(withId(id)), id);
        }
        assertFalse(SkyblockItems.isHealingWand(withId("ZOMBIE_SWORD")));
    }

    @Test
    void bothWhipsCountAsASoulWhip() {
        assertTrue(SkyblockItems.isSoulWhip(withId("SOUL_WHIP")));
        assertTrue(SkyblockItems.isSoulWhip(withId("FLAMING_FLAY")));
        assertFalse(SkyblockItems.isSoulWhip(withId("ROD_OF_THE_SEA")));
        assertFalse(SkyblockItems.isSoulWhip(withId("GRAPPLING_HOOK")));
    }

    @Test
    void theHeldSlotWinsThenTheLowestMatchingSlot() {
        List<ItemStack> hotbar = Arrays.asList(ItemStack.EMPTY, null, withId("SOUL_WHIP"), withId("HYPERION"),
                ItemStack.EMPTY, withId("ASTRAEA"), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        assertEquals(5, SkyblockItems.firstMatchingSlot(5, hotbar, SkyblockItems::isHyperion));
        assertEquals(3, SkyblockItems.firstMatchingSlot(0, hotbar, SkyblockItems::isHyperion));
        assertEquals(3, SkyblockItems.firstMatchingSlot(2, hotbar, SkyblockItems::isHyperion));
        assertEquals(3, SkyblockItems.firstMatchingSlot(-1, hotbar, SkyblockItems::isHyperion));
        assertEquals(3, SkyblockItems.firstMatchingSlot(9, hotbar, SkyblockItems::isHyperion));
        assertEquals(-1, SkyblockItems.firstMatchingSlot(0, hotbar, SkyblockItems::isHealingWand));
        assertEquals(2, SkyblockItems.firstMatchingSlot(1, hotbar, stack -> {
            assertFalse(stack.isEmpty());
            return SkyblockItems.isSoulWhip(stack);
        }));
    }
}
