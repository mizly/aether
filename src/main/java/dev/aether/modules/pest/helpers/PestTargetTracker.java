package dev.aether.modules.pest.helpers;

import dev.aether.util.ClientUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PestTargetTracker {
    private static final List<String> PEST_TEXTURE_FRAGMENTS = List.of(
            "70a1e836bf1968b2eaa4837227a19204f17295d870ee9e754bd6b6d60ddbed3c",
            "a24c69f96ce5562221e195c8ef2bfad71ebf7f95f5ae914a484a8d0ec21672674",
            "6403ba4027a333d8d2fd32ab59d1cfdbaa7d908d80d2381db2a69cbe65450ad8",
            "9d90e777826a52461368e26d1b2e19bfa1ba582d60248e545f4124d0f731842",
            "4b24a482a32db1ea78fb98060b0c2fa4a373cbd18a68edddeb7419455a59cda9",
            "be6baf6431a9daa2ca604d5a3c26e9a761d5952f0817174a4fe0b764616e21ff",
            "52a9fe05bc663efcd12e56a3ccc5ec035bf577b78708548b6f4ffcf1d30eccfe",
            "6545c4b34e5b5470be94de100e61f7816f81bc5a11dfdf0eccf890172da5d0a",
            "a8abb471db0ab78703011997dc8b40798a941f3a4dec3ec61cbeec2af8cffe8",
            "7a79d0fd677b54530961117ef84adc206e2cc5045c1344d61d776bf8ac2fe1ba",
            "1e04bb6367caa4e88f5fd0ee80f0745d137a604223dbbc42a16471fdf64bb83",
            "4ce69e90adf34718f313ec24d6c6135b69b3788c61849844666ccc83ca640c0b16",
            "254aff4c0b2dce3a672349cc0e99e6f3a9deebe4b3556e84611eca250a7821bf");

    private static Object[] entityBuffer = new Object[512];
    private static int entityBufferSize = 0;

    private PestTargetTracker() {
    }

    static Entity peekNextQueuedPest(Minecraft client, Deque<Entity> pestTargetQueue, Collection<Entity> killedEntities) {
        while (!pestTargetQueue.isEmpty()) {
            Entity next = pestTargetQueue.peekFirst();
            if (next == null || next.isRemoved() || (next instanceof LivingEntity le && le.isDeadOrDying())
                    || killedEntities.contains(next)
                    || (client != null && client.player != null && next == client.player)) {
                pestTargetQueue.pollFirst();
                continue;
            }
            return next;
        }
        return null;
    }

    static void rebuildPestTargetQueue(Minecraft client, Deque<Entity> pestTargetQueue, Collection<Entity> killedEntities) {
        if (client == null || client.level == null || client.player == null) {
            return;
        }

        List<Entity> pests = new ArrayList<>();
        Set<Integer> seenEntityIds = new HashSet<>();

        int count = fillEntityBuffer(client);
        for (int i = 0; i < count; i++) {
            Entity entity = (Entity) entityBuffer[i];
            if (entity == client.player || entity.isRemoved() || (entity instanceof LivingEntity le && le.isDeadOrDying()) || entity.getY() < 50) {
                continue;
            }
            if (killedEntities.contains(entity)) {
                continue;
            }

            Entity target = null;
            if (entity instanceof Bat || entity instanceof Silverfish) {
                target = entity;
            } else if (entity instanceof ArmorStand armorStand && isPestArmorStand(armorStand)) {
                Entity real = findRealEntityNear(client, armorStand);
                target = (real != null) ? real : (Entity) (Object) armorStand;
            }

            if (target == null || target.isRemoved() || (target instanceof LivingEntity le && le.isDeadOrDying()) || killedEntities.contains(target)) {
                continue;
            }
            if (seenEntityIds.add(target.getId())) {
                pests.add(target);
            }
        }

        final net.minecraft.client.player.LocalPlayer sortPlayer = client.player;
        if (sortPlayer != null) {
            pests.sort((a, b) -> Double.compare(sortPlayer.distanceToSqr(a), sortPlayer.distanceToSqr(b)));
        }

        pestTargetQueue.clear();
        pestTargetQueue.addAll(pests);

        if (!pests.isEmpty()) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Rebuilt target queue with " + pests.size() + " pest(s). Next: "
                            + formatPos(pests.get(0).position()));
        }
    }

    static int countAvailablePests(Minecraft client, Collection<Entity> killedEntities) {
        if (client == null || client.level == null || client.player == null) {
            return 0;
        }

        Set<Integer> seenEntityIds = new HashSet<>();

        int count = fillEntityBuffer(client);
        for (int i = 0; i < count; i++) {
            Entity entity = (Entity) entityBuffer[i];
            if (entity == client.player || entity.isRemoved() || (entity instanceof LivingEntity le && le.isDeadOrDying()) || entity.getY() < 50) {
                continue;
            }
            if (killedEntities.contains(entity)) {
                continue;
            }

            Entity target = null;
            if (entity instanceof Bat || entity instanceof Silverfish) {
                target = entity;
            } else if (entity instanceof ArmorStand armorStand && isPestArmorStand(armorStand)) {
                Entity real = findRealEntityNear(client, armorStand);
                target = (real != null) ? real : (Entity) (Object) armorStand;
            }

            if (target == null || target.isRemoved() || (target instanceof LivingEntity le && le.isDeadOrDying()) || killedEntities.contains(target)) {
                continue;
            }
            seenEntityIds.add(target.getId());
        }

        return seenEntityIds.size();
    }

    static Entity getNextQueuedPest(Minecraft client, Deque<Entity> pestTargetQueue, Collection<Entity> killedEntities) {
        while (!pestTargetQueue.isEmpty()) {
            Entity next = pestTargetQueue.pollFirst();
            if (next == null || next.isRemoved() || (next instanceof LivingEntity le && le.isDeadOrDying())
                    || killedEntities.contains(next)
                    || (client != null && client.player != null && next == client.player)) {
                continue;
            }
            return next;
        }
        return null;
    }

    static Entity findClosestPest(Minecraft client, Collection<Entity> killedEntities) {
        if (client.level == null || client.player == null) {
            return null;
        }

        Entity closest = null;
        double closestDist = Double.MAX_VALUE;
        int armorStandCount = 0;
        int batSilverfishCount = 0;
        int totalEntities = 0;
        int pestsFound = 0;

        int count = fillEntityBuffer(client);
        for (int i = 0; i < count; i++) {
            Entity entity = (Entity) entityBuffer[i];
            if (entity == client.player || entity.isRemoved() || (entity instanceof LivingEntity le && le.isDeadOrDying()) || entity.getY() < 50) {
                continue;
            }
            if (killedEntities.contains(entity)) {
                continue;
            }
            totalEntities++;

            boolean isPest = false;
            if (entity instanceof Bat || entity instanceof Silverfish) {
                batSilverfishCount++;
                isPest = true;
            } else if (entity instanceof ArmorStand armorStand) {
                armorStandCount++;
                isPest = isPestArmorStand(armorStand);
            }

            if (isPest) {
                pestsFound++;
                double dist = client.player.distanceToSqr(entity);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = entity;
                }
            }
        }

        ClientUtils.sendDebugMessage("[PestDestroyer] Scan: " + totalEntities + " entities, "
                        + armorStandCount + " armor stands, "
                        + batSilverfishCount + " bats/silverfish, "
                        + pestsFound + " pests found");

        if (closest instanceof ArmorStand) {
            Entity real = findRealEntityNear(client, closest);
            if (real != null) {
                closest = real;
            }
        }

        return closest;
    }

    static boolean hasPestArmorStandNearby(Minecraft client, Entity targetEntity) {
        int count = fillEntityBuffer(client);
        for (int i = 0; i < count; i++) {
            Entity other = (Entity) entityBuffer[i];
            if (!(other instanceof ArmorStand armorStand)) {
                continue;
            }
            if (other.distanceToSqr(targetEntity) > 4.0) {
                continue;
            }
            if (isPestArmorStand(armorStand)) {
                return true;
            }
        }
        return false;
    }

    static int countVisiblePestSkulls(Minecraft client) {
        if (client == null || client.level == null) {
            return 0;
        }
        int count = fillEntityBuffer(client);
        int skullCount = 0;
        for (int i = 0; i < count; i++) {
            Entity entity = (Entity) entityBuffer[i];
            if (!(entity instanceof ArmorStand armorStand)) {
                continue;
            }
            if (armorStand.isRemoved() || armorStand.getY() < 50) {
                continue;
            }
            if (isPestArmorStand(armorStand)) {
                skullCount++;
            }
        }
        return skullCount;
    }

    static boolean hasPestSkullMarkerForTarget(Minecraft client, Entity target) {
        if (client == null || client.level == null || target == null || target.isRemoved() || (target instanceof LivingEntity le && le.isDeadOrDying())) {
            return false;
        }
        if (target instanceof Bat || target instanceof Silverfish) {
            return true;
        }
        if (target instanceof ArmorStand armorStand) {
            return isPestArmorStand(armorStand);
        }
        return false;
    }

    private static boolean isPestArmorStand(ArmorStand armorStand) {
        ItemStack headItem = armorStand.getItemBySlot(EquipmentSlot.HEAD);
        if (headItem.isEmpty()) {
            return false;
        }
        if (headItem.has(DataComponents.CUSTOM_NAME)) {
            return false;
        }

        ResolvableProfile profile = headItem.get(DataComponents.PROFILE);
        if (profile == null) {
            return false;
        }

        var properties = profile.partialProfile().properties();
        if (properties == null) {
            return false;
        }

        var textures = properties.get("textures");
        if (textures == null) {
            return false;
        }

        for (var prop : textures) {
            String value = prop.value();
            if (value == null) {
                continue;
            }

            try {
                String decodedJson = new String(java.util.Base64.getDecoder().decode(value));
                for (String fragment : PEST_TEXTURE_FRAGMENTS) {
                    if (fragment != null && decodedJson.contains(fragment)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    private static Entity findRealEntityNear(Minecraft client, Entity armorStand) {
        java.util.Iterator rawIter = ((Iterable) client.level.entitiesForRendering()).iterator();
        try {
            while (rawIter.hasNext()) {
                Object raw = rawIter.next();
                if (!(raw instanceof Entity entity)) {
                    continue;
                }
                if (entity instanceof ArmorStand || entity == client.player) {
                    continue;
                }
                if (entity.distanceToSqr(armorStand) > 4.0) {
                    continue;
                }
                if (entity instanceof Bat || entity instanceof Silverfish) {
                    return entity;
                }
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            return null;
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private static int fillEntityBuffer(Minecraft client) {
        entityBufferSize = 0;
        if (client == null || client.level == null) {
            return 0;
        }
        java.util.Iterator rawIter = ((Iterable) client.level.entitiesForRendering()).iterator();
        try {
            while (rawIter.hasNext()) {
                Object raw = rawIter.next();
                if (raw instanceof Entity) {
                    if (entityBufferSize >= entityBuffer.length) {
                        entityBuffer = java.util.Arrays.copyOf(entityBuffer, entityBuffer.length * 2);
                    }
                    entityBuffer[entityBufferSize] = raw;
                    entityBufferSize++;
                }
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            return entityBufferSize;
        }
        return entityBufferSize;
    }

    private static String formatPos(Vec3 pos) {
        return String.format("%.0f, %.0f, %.0f", pos.x, pos.y, pos.z);
    }
}
