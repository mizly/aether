package dev.aether.modules.pest.helpers;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SprayonatorItemTest {
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

    @Test
    void recognizesEveryTierByIdEvenWithoutMaterialLoreOrEnglishName() {
        for (String id : List.of("SPRAYONATOR", "JUICY_SPRAYONATOR", "SALTY_SPRAYONATOR")) {
            ItemStack stack = new ItemStack(Items.PAPER);
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            assertTrue(SprayonatorItem.matches(stack));
            assertNull(SprayonatorItem.selectedMaterial(stack));
            CompoundTag legacy = new CompoundTag();
            legacy.put("ExtraAttributes", tag);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(legacy));
            assertTrue(SprayonatorItem.matches(stack));
        }
    }

    @Test
    void supportsFormattedTierNamesAndReadsMaterialAfterTheNewNozzleLine() {
        for (String name : List.of("§aSprayonator", "§9Juicy Sprayonator", "§5Salty Sprayonator")) {
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("§7Selected Nozzle: §5Salty"),
                    Component.literal("§7Selected Material: §aPlant Matter"))));
            assertTrue(SprayonatorItem.matches(stack));
            assertEquals("Plant Matter", SprayonatorItem.selectedMaterial(stack));
        }
    }

    @Test
    void rejectsNozzlesAndUnrelatedItemsMentioningSprayonators() {
        assertFalse(SprayonatorItem.matches("SALTY_NOZZLE", "Salty Sprayonator"));
        assertFalse(SprayonatorItem.matches("", "Sprayonator Upgrade"));
        assertFalse(SprayonatorItem.matches("", "Juicy Nozzle"));
        assertFalse(SprayonatorItem.matches(ItemStack.EMPTY));
    }

    @Test
    void handlesWhitespaceAndMissingSelectionsWithoutInventingAMaterial() {
        assertEquals("Dung", SprayonatorItem.selectedMaterial(List.of("  §7Selected\u00a0Material : §aDung  ")));
        assertNull(SprayonatorItem.selectedMaterial(List.of("Selected Material: None")));
        assertNull(SprayonatorItem.selectedMaterial(List.of("Selected Nozzle: Juicy")));
    }
}
