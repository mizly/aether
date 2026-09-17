package dev.aether.modules.pest.helpers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PestDisplayTracker {
    private static ClientLevel level;
    private static int tick = Integer.MIN_VALUE;
    private static List<PestDisplay> cached = List.of();
    private static final Map<Integer, Double> peakHealth = new HashMap<>();
    private static final Map<Integer, Integer> skullOwners = new HashMap<>();

    private PestDisplayTracker() { }

    public static List<PestDisplay> getPests(Minecraft client) {
        if (client.level != level || client.player == null) {
            level = client.level;
            tick = Integer.MIN_VALUE;
            cached = List.of();
            peakHealth.clear();
            skullOwners.clear();
        }
        if (level == null || client.player == null) return List.of();
        if (tick == client.player.tickCount) return livePests();
        tick = client.player.tickCount;
        List<Entity> mobs = PestTargetTracker.getLoadedPestMobs(client);
        List<ArmorStand> skulls = PestTargetTracker.getLoadedPestMarkers(client);
        List<Entity> preferredMobs = mobs.stream().filter(mob -> !isAttached(client, mob)).toList();
        Map<Entity, ArmorStand> visibleSkulls = new java.util.LinkedHashMap<>();
        for (ArmorStand skull : skulls) {
            Integer previousOwner = skullOwners.get(skull.getId());
            Entity owner = findSkullOwner(skull, previousOwner, mobs, preferredMobs);
            if (owner != null) {
                visibleSkulls.putIfAbsent(owner, skull);
                skullOwners.put(skull.getId(), owner.getId());
            }
        }
        skullOwners.keySet().removeIf(id -> skulls.stream().noneMatch(skull -> skull.getId() == id));
        List<Entity> displayed = new ArrayList<>(visibleSkulls.keySet());
        for (Entity mob : mobs) {
            if (!visibleSkulls.containsKey(mob) && !(isAttached(client, mob)
                    && displayed.stream().anyMatch(other -> other.distanceToSqr(mob) < 9))) {
                displayed.add(mob);
            }
        }
        Map<Entity, List<ArmorStand>> markers = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || stand.isRemoved()) continue;
            Entity owner = nearest(stand, displayed);
            if (owner != null) markers.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(stand);
        }
        List<PestDisplay> result = new ArrayList<>();
        for (Entity mob : displayed) {
            List<ArmorStand> nearby = markers.getOrDefault(mob, List.of());
            ArmorStand skull = visibleSkulls.get(mob);
            String name = "Pest";
            PestDisplayValues.Health health = null;
            float progress = -1f;
            boolean attached = mobs.stream().anyMatch(helper -> helper instanceof Leashable leash
                    && leash.getLeashHolder() == client.player && helper.distanceToSqr(mob) <= 9
                    && displayed.stream().min(Comparator.comparingDouble(helper::distanceToSqr)).orElse(null) == mob);
            List<Component> labels = new ArrayList<>();
            if (mob.getCustomName() != null) labels.add(mob.getDisplayName());
            nearby.forEach(marker -> labels.add(marker.getDisplayName()));
            for (Component label : labels) {
                int type = PestHuntingPolicy.findPestTypeIndex(label.getString());
                if (type >= 0) name = PestHuntingPolicy.PEST_TYPES.get(type);
                PestDisplayValues.Health parsed = PestDisplayValues.health(label.getString());
                if (parsed != null && type >= 0) health = parsed;
                float parsedProgress = PestDisplayValues.huntingProgress(label);
                if (parsedProgress >= 0) progress = Math.max(progress, parsedProgress);
            }
            float fraction = -1f;
            double current = -1;
            if (health != null) {
                current = health.current();
                double maximum = health.maximum() > 0 ? health.maximum()
                        : peakHealth.merge(mob.getId(), current, Math::max);
                fraction = maximum > 0 ? (float) Math.clamp(current / maximum, 0, 1) : 0f;
            }
            boolean hunting = attached || PestHuntingPolicy.shouldLasso(client, mob);
            if (hunting && !attached && progress < 0) progress = 0f;
            ItemStack icon = skull == null ? new ItemStack(Items.PLAYER_HEAD) : skull.getItemBySlot(EquipmentSlot.HEAD).copy();
            result.add(new PestDisplay(mob, skull, name, icon, current, fraction, hunting, progress, attached));
        }
        result.sort(Comparator.comparingInt(pest -> pest.entity().getId()));
        peakHealth.keySet().removeIf(id -> mobs.stream().noneMatch(mob -> mob.getId() == id));
        cached = List.copyOf(result);
        return livePests();
    }

    private static List<PestDisplay> livePests() {
        if (cached.stream().anyMatch(pest -> pest.entity().isRemoved() || !pest.entity().isAlive())) {
            cached = cached.stream().filter(pest -> !pest.entity().isRemoved() && pest.entity().isAlive()).toList();
        }
        return cached;
    }

    private static boolean isAttached(Minecraft client, Entity entity) {
        return entity instanceof Leashable leash && leash.getLeashHolder() == client.player;
    }

    static Entity findSkullOwner(Entity skull, Integer previousOwner, List<Entity> mobs, List<Entity> preferredMobs) {
        if (previousOwner != null) {
            return mobs.stream().filter(mob -> mob.getId() == previousOwner).findFirst().orElse(null);
        }
        for (Entity vehicle = skull.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle()) {
            if (mobs.contains(vehicle)) return vehicle;
        }
        Entity owner = nearest(skull, preferredMobs);
        return owner != null ? owner : nearest(skull, mobs);
    }

    private static Entity nearest(Entity marker, List<Entity> mobs) {
        return mobs.stream().filter(mob -> isMarkerNear(marker, mob))
                .min(Comparator.<Entity>comparingDouble(marker::distanceToSqr).thenComparingInt(Entity::getId))
                .orElse(null);
    }

    private static boolean isMarkerNear(Entity marker, Entity mob) {
        double dx = marker.getX() - mob.getX(), dz = marker.getZ() - mob.getZ();
        double dy = marker.getY() - mob.getY();
        return dx * dx + dz * dz <= 2.25 && dy >= -2 && dy <= 5;
    }

    public record PestDisplay(Entity entity, ArmorStand skull, String name, ItemStack icon,
                              double health, float healthFraction, boolean hunting,
                              float progress, boolean attached) { }
}
