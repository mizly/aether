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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.function.Predicate;

public final class PestTargetTracker {
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

    private static int cachedTick = Integer.MIN_VALUE;
    private static int cachedLevelId = 0;
    private static PestSnapshot cachedSnapshot = PestSnapshot.EMPTY;

    private PestTargetTracker() {
    }

    static Entity peekNextQueuedPest(
            Minecraft client,
            Deque<Entity> pestTargetQueue,
            Collection<Entity> killedEntities,
            Predicate<Entity> eligible
    ) {
        while (!pestTargetQueue.isEmpty()) {
            Entity next = pestTargetQueue.peekFirst();
            if (isUnavailable(client, next, killedEntities) || !eligible.test(next)) {
                pestTargetQueue.pollFirst();
                continue;
            }
            return next;
        }
        return null;
    }

    static Entity getNextQueuedPest(
            Minecraft client,
            Deque<Entity> pestTargetQueue,
            Collection<Entity> killedEntities,
            Predicate<Entity> eligible
    ) {
        while (!pestTargetQueue.isEmpty()) {
            Entity next = pestTargetQueue.pollFirst();
            if (!isUnavailable(client, next, killedEntities) && eligible.test(next)) {
                return next;
            }
        }
        return null;
    }

    static void rebuildPestTargetQueue(
            Minecraft client,
            Deque<Entity> pestTargetQueue,
            Collection<Entity> killedEntities,
            int reservedEntityId,
            Predicate<Entity> eligible
    ) {
        List<Entity> pests = availableTargets(client, killedEntities, eligible);
        if (reservedEntityId != -1) {
            Map<Entity, Double> nearestNeighborDistances = new IdentityHashMap<>();
            List<Vec3> positions = pests.stream().map(Entity::position).toList();
            for (int i = 0; i < pests.size(); i++) {
                nearestNeighborDistances.put(pests.get(i), nearestNeighborDistanceSqr(i, positions));
            }
            pests.removeIf(pest -> pest.getId() == reservedEntityId);
            pests.sort(Comparator
                    .comparingDouble((Entity pest) -> nearestNeighborDistances.get(pest))
                    .thenComparingDouble(client.player::distanceToSqr));
        } else if (client.player != null) {
            pests.sort(Comparator.comparingDouble(client.player::distanceToSqr));
        }
        pestTargetQueue.clear();
        pestTargetQueue.addAll(pests);
        if (!pests.isEmpty()) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Rebuilt target queue with " + pests.size()
                    + " pest(s). Next: " + formatPos(pests.getFirst().position()));
        }
    }

    static Entity findClosestPest(
            Minecraft client,
            Collection<Entity> killedEntities,
            int reservedEntityId,
            Predicate<Entity> eligible) {
        if (client == null || client.player == null) {
            return null;
        }
        List<Entity> targets = availableTargets(client, killedEntities, eligible);
        Entity closest = targets.stream()
                .filter(target -> target.getId() != reservedEntityId)
                .min(Comparator.comparingDouble(client.player::distanceToSqr))
                .orElse(null);
        PestSnapshot snapshot = snapshot(client);
        ClientUtils.sendDebugMessage("[PestDestroyer] Scan: " + snapshot.rawEntityCount()
                + " entities, " + snapshot.markers().size() + " pest markers, "
                + targets.size() + " available pests");
        return closest;
    }

    static Entity findMostIsolatedPest(
            Minecraft client,
            Collection<Entity> killedEntities,
            Predicate<Entity> eligible) {
        List<Entity> pests = availableTargets(client, killedEntities, eligible);
        if (pests.size() < 2) {
            return null;
        }
        List<Vec3> positions = pests.stream().map(Entity::position).toList();
        return pests.get(mostIsolatedIndex(positions));
    }

    static boolean isAvailablePest(
            Minecraft client,
            Collection<Entity> killedEntities,
            int entityId,
            Predicate<Entity> eligible) {
        return availableTargets(client, killedEntities, eligible).stream()
                .anyMatch(pest -> pest.getId() == entityId);
    }

    // ponytail: O(n^2) is simpler and pests are single-digit; spatial indexing only if that ceiling changes.
    static int mostIsolatedIndex(List<Vec3> positions) {
        int mostIsolated = -1;
        double bestDistance = -1.0;
        for (int i = 0; i < positions.size(); i++) {
            double distance = nearestNeighborDistanceSqr(i, positions);
            if (distance > bestDistance) {
                bestDistance = distance;
                mostIsolated = i;
            }
        }
        return mostIsolated;
    }

    private static double nearestNeighborDistanceSqr(int index, List<Vec3> positions) {
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < positions.size(); i++) {
            if (i != index) {
                nearest = Math.min(nearest, positions.get(index).distanceToSqr(positions.get(i)));
            }
        }
        return nearest;
    }

    static boolean hasPestArmorStandNearby(Minecraft client, Entity targetEntity) {
        if (targetEntity == null) {
            return false;
        }
        return snapshot(client).markers().stream()
                .anyMatch(marker -> !marker.isRemoved() && marker.distanceToSqr(targetEntity) <= 4.0);
    }

    static boolean hasPestSkullMarkerForTarget(Minecraft client, Entity target) {
        if (isUnavailable(client, target, List.of())) {
            return false;
        }
        if (target instanceof Bat || target instanceof Silverfish) {
            return true;
        }
        if (target instanceof ArmorStand armorStand) {
            return snapshot(client).markers().contains(armorStand);
        }
        return false;
    }

    public static Entity selectClosestPestWithin(
            Minecraft client,
            double maxDistFromEye,
            Predicate<Entity> condition
    ) {
        if (client == null || client.player == null || maxDistFromEye < 0.0) {
            return null;
        }
        Vec3 eyePosition = client.player.getEyePosition();
        double maxDistanceSq = maxDistFromEye * maxDistFromEye;
        Entity closest = null;
        double closestDistanceSq = Double.MAX_VALUE;
        for (Entity target : snapshot(client).targets()) {
            if (condition != null && !condition.test(target)) {
                continue;
            }
            Vec3 targetEye = target.position().add(0, target.getEyeHeight(target.getPose()), 0);
            double distanceSq = eyePosition.distanceToSqr(targetEye);
            if (distanceSq <= maxDistanceSq && distanceSq < closestDistanceSq) {
                closest = target;
                closestDistanceSq = distanceSq;
            }
        }
        return closest;
    }

    /** Returns the pest entities currently visible to the client. */
    public static List<Entity> getLoadedPests(Minecraft client) {
        return snapshot(client).targets();
    }

    public static List<Entity> getLoadedPestMobs(Minecraft client) {
        return snapshot(client).targets().stream()
                .filter(entity -> entity instanceof Bat || entity instanceof Silverfish)
                .filter(entity -> !isUnavailable(client, entity, List.of()))
                .toList();
    }

    private static List<Entity> availableTargets(
            Minecraft client,
            Collection<Entity> killedEntities,
            Predicate<Entity> eligible) {
        List<Entity> available = new ArrayList<>();
        for (Entity target : snapshot(client).targets()) {
            if (!isUnavailable(client, target, killedEntities) && eligible.test(target)) {
                available.add(target);
            }
        }
        return available;
    }

    private static boolean isUnavailable(
            Minecraft client,
            Entity entity,
            Collection<Entity> killedEntities
    ) {
        return entity == null
                || entity.isRemoved()
                || entity.getY() < 50
                || entity instanceof LivingEntity living && living.isDeadOrDying()
                || killedEntities.contains(entity)
                || client != null && client.player != null && entity == client.player;
    }

    private static PestSnapshot snapshot(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            return PestSnapshot.EMPTY;
        }
        if (!client.isSameThread()) {
            return PestClientThread.call(client, () -> snapshot(client), PestSnapshot.EMPTY);
        }

        int tick = client.player.tickCount;
        int levelId = System.identityHashCode(client.level);
        if (cachedTick == tick && cachedLevelId == levelId) {
            return cachedSnapshot;
        }

        List<Entity> rawEntities = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            rawEntities.add(entity);
        }

        List<ArmorStand> markers = rawEntities.stream()
                .filter(ArmorStand.class::isInstance)
                .map(ArmorStand.class::cast)
                .filter(marker -> !marker.isRemoved() && marker.getY() >= 50 && isPestArmorStand(marker))
                .toList();
        Map<Integer, Entity> targetsById = new LinkedHashMap<>();
        for (Entity entity : rawEntities) {
            if (entity == client.player || entity.isRemoved() || entity.getY() < 50) {
                continue;
            }
            Entity target = null;
            if (entity instanceof Bat || entity instanceof Silverfish) {
                target = entity;
            } else if (entity instanceof ArmorStand marker && markers.contains(marker)) {
                target = findRealEntityNear(rawEntities, marker);
                if (target == null) {
                    target = marker;
                }
            }
            if (target != null && !isUnavailable(client, target, List.of())) {
                targetsById.putIfAbsent(target.getId(), target);
            }
        }

        cachedTick = tick;
        cachedLevelId = levelId;
        cachedSnapshot = new PestSnapshot(List.copyOf(targetsById.values()), markers, rawEntities.size());
        return cachedSnapshot;
    }

    private static Entity findRealEntityNear(List<Entity> entities, ArmorStand armorStand) {
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : entities) {
            if (!(entity instanceof Bat || entity instanceof Silverfish) || entity.isRemoved()) {
                continue;
            }
            double distance = entity.distanceToSqr(armorStand);
            if (distance <= 4.0 && distance < closestDistance) {
                closest = entity;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private static boolean isPestArmorStand(ArmorStand armorStand) {
        ItemStack headItem = armorStand.getItemBySlot(EquipmentSlot.HEAD);
        if (headItem.isEmpty() || headItem.has(DataComponents.CUSTOM_NAME)) {
            return false;
        }
        ResolvableProfile profile = headItem.get(DataComponents.PROFILE);
        if (profile == null || profile.partialProfile().properties() == null) {
            return false;
        }
        var textures = profile.partialProfile().properties().get("textures");
        if (textures == null) {
            return false;
        }

        for (var property : textures) {
            try {
                String decoded = new String(
                        java.util.Base64.getDecoder().decode(property.value()),
                        StandardCharsets.UTF_8);
                if (PEST_TEXTURE_FRAGMENTS.stream().anyMatch(decoded::contains)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                ClientUtils.sendDebugMessage("Pest target marker contained an invalid base64 texture.");
            }
        }
        return false;
    }

    private static String formatPos(Vec3 pos) {
        return String.format("%.0f, %.0f, %.0f", pos.x, pos.y, pos.z);
    }

    private record PestSnapshot(List<Entity> targets, List<ArmorStand> markers, int rawEntityCount) {
        private static final PestSnapshot EMPTY = new PestSnapshot(List.of(), List.of(), 0);
    }
}
