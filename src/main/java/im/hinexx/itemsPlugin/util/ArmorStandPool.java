package im.hinexx.itemsPlugin.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reuses armor stands across Dizorent casts to avoid spawn/despawn spikes.
 */
public final class ArmorStandPool {

    private final Map<UUID, ArrayDeque<ArmorStand>> freeByWorld = new ConcurrentHashMap<>();
    private final Set<ArmorStand> all = ConcurrentHashMap.newKeySet();
    private final int hardCap;

    public ArmorStandPool(int hardCap) {
        this.hardCap = Math.max(32, hardCap);
    }

    public ArmorStand acquire(World world, Location at, ItemStack helmet, EulerAngle headPose) {
        ArrayDeque<ArmorStand> free = freeByWorld.computeIfAbsent(world.getUID(), id -> new ArrayDeque<>());
        ArmorStand stand = null;

        while (!free.isEmpty()) {
            ArmorStand candidate = free.pollFirst();
            if (candidate != null && candidate.isValid() && candidate.getWorld().equals(world)) {
                stand = candidate;
                break;
            }
            if (candidate != null) {
                discard(candidate);
            }
        }

        if (stand == null) {
            if (all.size() >= hardCap) {
                return null;
            }
            stand = create(world, at, helmet, headPose);
            all.add(stand);
            return stand;
        }

        configure(stand, helmet, headPose);
        stand.teleport(at);
        return stand;
    }

    public void release(ArmorStand stand) {
        if (stand == null) {
            return;
        }
        if (!stand.isValid()) {
            all.remove(stand);
            return;
        }
        World world = stand.getWorld();
        stand.teleport(park(world));
        freeByWorld.computeIfAbsent(world.getUID(), id -> new ArrayDeque<>()).offerLast(stand);
    }

    public void releaseAll(Iterable<ArmorStand> stands) {
        for (ArmorStand stand : stands) {
            release(stand);
        }
    }

    public void shutdown() {
        for (ArmorStand stand : new HashSet<>(all)) {
            if (stand.isValid()) {
                stand.remove();
            }
        }
        all.clear();
        freeByWorld.clear();
    }

    public int size() {
        return all.size();
    }

    private ArmorStand create(World world, Location at, ItemStack helmet, EulerAngle headPose) {
        return world.spawn(at, ArmorStand.class, stand -> configure(stand, helmet, headPose));
    }

    private void configure(ArmorStand stand, ItemStack helmet, EulerAngle headPose) {
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setInvulnerable(true);
        stand.setCustomNameVisible(false);
        stand.setCollidable(false);
        stand.setPersistent(false);
        stand.setSilent(true);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setAI(false);
        stand.setRemoveWhenFarAway(false);
        EntityEquipment equipment = stand.getEquipment();
        if (equipment != null) {
            equipment.setHelmet(helmet);
        }
        stand.setHeadPose(headPose);
    }

    private void discard(ArmorStand stand) {
        all.remove(stand);
        if (stand.isValid()) {
            stand.remove();
        }
    }

    private static Location park(World world) {
        // Far below world to keep out of player view / ticking interest
        return new Location(world, 0.5, world.getMinHeight() - 16.0, 0.5);
    }
}
