package im.hinexx.itemsPlugin.util;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.logging.Logger;

public final class TrapRoofFiller {

    private static Logger logger;

    private TrapRoofFiller() {
    }

    public static void setLogger(Logger log) {
        logger = log;
    }

    /** Y of the solid block the player is standing on. */
    public static int groundY(Location feet) {
        World world = feet.getWorld();
        if (world == null) {
            return feet.getBlockY() - 1;
        }
        int x = feet.getBlockX();
        int z = feet.getBlockZ();
        int start = feet.getBlockY();
        for (int y = start; y >= start - 4; y--) {
            if (isFillCandidate(world.getBlockAt(x, y, z).getType())) {
                return y;
            }
        }
        return Math.max(world.getMinHeight() + 1, start - 1);
    }

    public static BlockData[][] sampleRoof(World world, int centerX, int groundY, int centerZ, int half) {
        int size = half * 2 + 1;
        BlockData[][] data = new BlockData[size][size];
        BlockData fallback = fallback(world).createBlockData();
        BlockData outside = sampleOutside(world, centerX, groundY, centerZ, half);
        if (outside == null) {
            outside = fallback;
        }

        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                BlockData cell = sampleCell(world, centerX + dx, groundY, centerZ + dz);
                data[dx + half][dz + half] = cell != null ? cell : outside.clone();
            }
        }
        return data;
    }

    private static BlockData sampleCell(World world, int x, int groundY, int z) {
        for (int y = groundY + 1; y >= groundY - 2; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (isFillCandidate(b.getType())) {
                return b.getBlockData().clone();
            }
        }
        return null;
    }

    private static BlockData sampleOutside(World world, int cx, int groundY, int cz, int digHalf) {
        for (int r = digHalf + 1; r <= digHalf + 4; r++) {
            for (int ox = -r; ox <= r; ox++) {
                for (int oz = -r; oz <= r; oz++) {
                    if (Math.abs(ox) <= digHalf && Math.abs(oz) <= digHalf) {
                        continue;
                    }
                    Block b = world.getBlockAt(cx + ox, groundY, cz + oz);
                    if (isFillCandidate(b.getType())) {
                        return b.getBlockData().clone();
                    }
                    Block below = world.getBlockAt(cx + ox, groundY - 1, cz + oz);
                    if (isFillCandidate(below.getType())) {
                        return below.getBlockData().clone();
                    }
                }
            }
        }
        return null;
    }

    private static boolean isFillCandidate(Material type) {
        if (type.isAir() || !type.isBlock() || !type.isSolid()) {
            return false;
        }
        String name = type.name();
        return !(name.contains("LEAVE") || name.contains("LOG") || name.contains("FENCE")
                || name.contains("DOOR") || name.contains("SIGN") || name.contains("BANNER")
                || name.contains("CHEST") || name.contains("SHULKER") || name.contains("BED")
                || name.equals("TNT") || name.contains("TORCH") || name.contains("BUTTON")
                || name.contains("SLAB") || name.contains("STAIR") || name.contains("CARPET")
                || name.contains("PRESSURE") || name.contains("RAIL"));
    }

    private static Material fallback(World world) {
        return switch (world.getEnvironment()) {
            case THE_END -> Material.END_STONE;
            case NETHER -> Material.NETHERRACK;
            default -> Material.STONE;
        };
    }

    /**
     * Solid seal at roofY only — never touches blocks above the player/canopy.
     */
    public static void seal(World world, int centerX, int roofY, int centerZ, int half, BlockData[][] sampled) {
        BlockData fallback = fallback(world).createBlockData();
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    if (ProtectionHook.shouldSkipBlock(world, x, roofY, z)) {
                        continue;
                    }
                    BlockData data = null;
                    if (sampled != null) {
                        data = sampled[dx + half][dz + half];
                    }
                    if (data == null || data.getMaterial().isAir() || !data.getMaterial().isSolid()) {
                        data = fallback;
                    }
                    editSession.setBlock(BlockVector3.at(x, roofY, z), BukkitAdapter.adapt(data));
                }
            }
        } catch (WorldEditException e) {
            warn("Trap seal failed: " + e.getMessage());
        }
    }

    public static void clear(World world, int centerX, int roofY, int centerZ, int half) {
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);
            var air = BlockTypes.AIR.getDefaultState();
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    if (ProtectionHook.shouldSkipBlock(world, x, roofY, z)) {
                        continue;
                    }
                    editSession.setBlock(BlockVector3.at(x, roofY, z), air);
                }
            }
        } catch (WorldEditException e) {
            warn("Trap clear failed: " + e.getMessage());
        }
    }

    /** Снять камуфляж + слой крыши схемы под ним (дерево сверху не трогаем). */
    public static void openExit(World world, int centerX, int groundY, int centerZ, int half) {
        clear(world, centerX, groundY, centerZ, half);
        clear(world, centerX, groundY - 1, centerZ, half);
    }

    /** Dig downward only: from {@code topY} down {@code depth} blocks. */
    public static void digShaft(World world, int centerX, int topY, int centerZ, int half, int depth) {
        int bottom = topY - depth + 1;
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);
            var air = BlockTypes.AIR.getDefaultState();
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    for (int y = bottom; y <= topY; y++) {
                        int x = centerX + dx;
                        int z = centerZ + dz;
                        if (ProtectionHook.shouldSkipBlock(world, x, y, z)) {
                            continue;
                        }
                        editSession.setBlock(BlockVector3.at(x, y, z), air);
                    }
                }
            }
        } catch (WorldEditException e) {
            warn("Trap dig failed: " + e.getMessage());
        }
    }

    /**
     * Safe eject spot: 2-high clear space, leaves/logs count as blocked.
     * Spirals out so under a tree we exit beside the canopy instead of bouncing into it.
     * Does not break any blocks above the player.
     */
    public static Location findSafeExit(World world, int cx, int cz, int groundY, float yaw, float pitch) {
        if (world == null) {
            return null;
        }
        for (int r = 0; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = cx + dx;
                    int z = cz + dz;
                    for (int feetY = groundY + 1; feetY <= groundY + 12; feetY++) {
                        if (!isEjectClear(world, x, feetY, z) || !isEjectClear(world, x, feetY + 1, z)) {
                            continue;
                        }
                        Material below = world.getBlockAt(x, feetY - 1, z).getType();
                        // Prefer standing on solid / just above opened roof
                        if (feetY == groundY + 1 || below.isSolid()) {
                            return new Location(world, x + 0.5, feetY, z + 0.5, yaw, pitch);
                        }
                    }
                }
            }
        }
        // Last resort: above highest block (canopy top) — still no breaking
        int top = world.getHighestBlockYAt(cx, cz) + 1;
        return new Location(world, cx + 0.5, top, cz + 0.5, yaw, pitch);
    }

    /** Teleport without upward kick into leaves; optional tiny hop only if clear above. */
    public static void ejectPlayer(Player player, Location exit) {
        if (player == null || !player.isOnline() || exit == null || exit.getWorld() == null) {
            return;
        }
        Location dest = exit.clone();
        dest.setYaw(player.getYaw());
        dest.setPitch(player.getPitch());
        player.teleport(dest);
        player.setFallDistance(0f);

        World w = dest.getWorld();
        int x = dest.getBlockX();
        int y = dest.getBlockY();
        int z = dest.getBlockZ();
        boolean clearAbove = isEjectClear(w, x, y + 2, z) && isEjectClear(w, x, y + 3, z);
        if (clearAbove) {
            player.setVelocity(new Vector(0, 0.35, 0));
        } else {
            player.setVelocity(new Vector(0, 0, 0));
        }
    }

    /** If restore put solid back on the player — nudge to a safe spot again. */
    public static void unstuckIfNeeded(Player player, int cx, int cz, int groundY) {
        if (player == null || !player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        Location loc = player.getLocation();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        if (isEjectClear(world, x, y, z) && isEjectClear(world, x, y + 1, z)) {
            return;
        }
        Location safe = findSafeExit(world, cx, cz, groundY, player.getYaw(), player.getPitch());
        ejectPlayer(player, safe);
    }

    private static boolean isEjectClear(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        Material type = world.getBlockAt(x, y, z).getType();
        if (type.isAir()) {
            return true;
        }
        String name = type.name();
        if (name.contains("LEAVE") || name.contains("LOG") || name.contains("STEM")
                || name.contains("FENCE") || name.contains("WALL") || name.contains("GLASS")
                || name.contains("DOOR") || name.contains("TRAPDOOR")) {
            return false;
        }
        return !type.isSolid();
    }

    private static void warn(String msg) {
        if (logger != null) {
            logger.warning(msg);
        }
    }
}
