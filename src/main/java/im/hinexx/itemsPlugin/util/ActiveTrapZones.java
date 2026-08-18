package im.hinexx.itemsPlugin.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ActiveTrapZones {

    public record Zone(
            String id,
            String worldName,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            UUID owner
    ) {
        public boolean contains(Location loc) {
            if (loc == null || loc.getWorld() == null) {
                return false;
            }
            if (!loc.getWorld().getName().equals(worldName)) {
                return false;
            }
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY + 1
                    && z >= minZ && z <= maxZ;
        }

        public static Zone fromVolume(String id, World world, int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ, UUID owner) {
            return new Zone(id, world.getName(), minX, minY, minZ, maxX, maxY, maxZ, owner);
        }

        public static Zone cuboid(String id, World world, int cx, int cy, int cz,
                                  int halfX, int halfYDown, int halfYUp, int halfZ, UUID owner) {
            return new Zone(
                    id,
                    world.getName(),
                    cx - halfX,
                    cy - halfYDown,
                    cz - halfZ,
                    cx + halfX,
                    cy + halfYUp,
                    cz + halfZ,
                    owner
            );
        }
    }

    private static final Map<String, Zone> ZONES = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> OWNED = new ConcurrentHashMap<>();

    private ActiveTrapZones() {
    }

    public static void register(Zone zone) {
        if (zone == null || zone.id() == null) {
            return;
        }
        Zone prev = ZONES.put(zone.id(), zone);
        if (prev != null && prev.owner() != null) {
            decOwned(prev.owner());
        }
        if (zone.owner() != null) {
            OWNED.computeIfAbsent(zone.owner(), u -> new AtomicInteger()).incrementAndGet();
        }
    }

    /**
     * @return {@code true} if this was the owner's last active trap
     */
    public static boolean unregister(String id) {
        Zone zone = ZONES.remove(id);
        if (zone == null) {
            return false;
        }
        if (zone.owner() == null) {
            return false;
        }
        return decOwned(zone.owner()) <= 0;
    }

    private static int decOwned(UUID owner) {
        AtomicInteger counter = OWNED.get(owner);
        if (counter == null) {
            return 0;
        }
        int left = counter.decrementAndGet();
        if (left <= 0) {
            OWNED.remove(owner, counter);
            return 0;
        }
        return left;
    }

    public static boolean isInsideAnyTrap(Player player) {
        if (player == null) {
            return false;
        }
        Location loc = player.getLocation();
        for (Zone zone : ZONES.values()) {
            if (zone.contains(loc)) {
                return true;
            }
        }
        return false;
    }

    public static boolean ownsActiveTrap(UUID owner) {
        if (owner == null) {
            return false;
        }
        AtomicInteger c = OWNED.get(owner);
        return c != null && c.get() > 0;
    }

    /**
     * Can't start a new trap while already owning an active one.
     * Also blocked while standing inside another trap unless {@code allowUseInTrap}.
     */
    public static boolean cannotStartTrap(Player player, boolean allowUseInTrap) {
        if (player == null) {
            return true;
        }
        if (ownsActiveTrap(player.getUniqueId())) {
            return true;
        }
        return !allowUseInTrap && isInsideAnyTrap(player);
    }

    /** @deprecated use {@link #cannotStartTrap(Player, boolean)} */
    @Deprecated
    public static boolean cannotStartTrap(Player player) {
        return cannotStartTrap(player, false);
    }

    public static void clearAll() {
        ZONES.clear();
        OWNED.clear();
    }
}
