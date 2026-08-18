package im.hinexx.itemsPlugin.util;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

public final class ProtectionHook {

    private static Logger logger;
    private static boolean worldGuard;
    private static Method psIsProtectBlock;
    private static Method psRegionFromLocation;
    private static boolean requireBuild = true;
    private static boolean protectPsBlocks = true;
    private static boolean allowUseInPs = false;
    private static boolean protectContainers = true;

    private ProtectionHook() {
    }

    public static void init(Logger log, boolean requireBuildFlag, boolean protectPs,
                            boolean allowUseInPsFlag) {
        logger = log;
        requireBuild = requireBuildFlag;
        protectPsBlocks = protectPs;
        allowUseInPs = allowUseInPsFlag;

        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        worldGuard = wg != null && wg.isEnabled();

        psIsProtectBlock = null;
        psRegionFromLocation = null;
        Plugin ps = Bukkit.getPluginManager().getPlugin("ProtectionStones");
        if (ps != null && ps.isEnabled()) {
            try {
                Class<?> clazz = Class.forName("dev.espi.protectionstones.ProtectionStones");
                psIsProtectBlock = clazz.getMethod("isProtectBlock", Block.class);
                if (logger != null) {
                    logger.info("ProtectionStones hooked — PS stones will not be broken by traps");
                }
            } catch (ReflectiveOperationException e) {
                if (logger != null) {
                    logger.warning("ProtectionStones present but API not linked: " + e.getMessage());
                }
            }
            try {
                Class<?> regionClazz = Class.forName("dev.espi.protectionstones.PSRegion");
                psRegionFromLocation = regionClazz.getMethod("fromLocation", Location.class);
            } catch (ReflectiveOperationException e) {
                if (logger != null) {
                    logger.warning("ProtectionStones region lookup unavailable: " + e.getMessage());
                }
            }
        }

        if (logger != null) {
            logger.info("Protection: WorldGuard=" + worldGuard
                    + " ProtectionStones=" + (psIsProtectBlock != null)
                    + " use-trap-in-region=" + requireBuild
                    + " protect-ps-blocks=" + protectPsBlocks
                    + " allow_use_in_ps=" + allowUseInPs);
        }
    }

    public static void reload(boolean requireBuildFlag, boolean protectPs, boolean allowUseInPsFlag) {
        requireBuild = requireBuildFlag;
        protectPsBlocks = protectPs;
        allowUseInPs = allowUseInPsFlag;
    }

    public static boolean isProtectStone(World world, int x, int y, int z) {
        if (!protectPsBlocks || world == null || psIsProtectBlock == null) {
            return false;
        }
        return isProtectStone(world.getBlockAt(x, y, z));
    }

    public static boolean isProtectStone(Block block) {
        if (!protectPsBlocks || block == null || psIsProtectBlock == null) {
            return false;
        }
        try {
            Object result = psIsProtectBlock.invoke(null, block);
            return result instanceof Boolean b && b;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static boolean isProtectStone(World world, BlockVector3 pos) {
        return pos != null && isProtectStone(world, pos.x(), pos.y(), pos.z());
    }

    public static boolean isInProtectionStonesRegion(Location at) {
        if (at == null || at.getWorld() == null || psRegionFromLocation == null) {
            return false;
        }
        try {
            Object region = psRegionFromLocation.invoke(null, at);
            return region != null;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static boolean isContainerBlock(Block block) {
        if (block == null) {
            return false;
        }
        if (block.getState() instanceof Container) {
            return true;
        }
        Material type = block.getType();
        String name = type.name();
        return name.contains("CHEST")
                || name.contains("SHULKER")
                || name.contains("BARREL")
                || name.contains("HOPPER")
                || name.contains("DROPPER")
                || name.contains("DISPENSER")
                || name.contains("FURNACE")
                || name.contains("SMOKER")
                || name.contains("BLAST_FURNACE")
                || name.equals("BREWING_STAND")
                || name.contains("CAMPFIRE")
                || name.equals("JUKEBOX")
                || name.equals("LECTERN")
                || name.contains("BEEHIVE")
                || name.equals("BEE_NEST");
    }

    /** Skip this cell in dig/clear/paste — PS stone or inventory container. */
    public static boolean shouldSkipBlock(World world, int x, int y, int z) {
        if (world == null) {
            return false;
        }
        if (isProtectStone(world, x, y, z)) {
            return true;
        }
        if (!protectContainers) {
            return false;
        }
        return isContainerBlock(world.getBlockAt(x, y, z));
    }

    public static boolean shouldSkipBlock(World world, BlockVector3 pos) {
        return pos != null && shouldSkipBlock(world, pos.x(), pos.y(), pos.z());
    }

    /**
     * Whether the player may activate a terrain-modifying trap at {@code at}.
     * OPS / WG bypass always allowed.
     */
    public static boolean canUseTrapHere(Player player, Location at) {
        if (player == null || at == null || at.getWorld() == null) {
            return false;
        }
        if (player.hasPermission("vtraps.protection.bypass") || player.isOp()) {
            return true;
        }
        if (!allowUseInPs && isInProtectionStonesRegion(at)) {
            return false;
        }
        if (!requireBuild || !worldGuard) {
            return true;
        }
        return canBuild(player, at);
    }

    public static boolean canBuild(Player player, Location at) {
        if (player == null || at == null || at.getWorld() == null) {
            return false;
        }
        if (!worldGuard) {
            return true;
        }
        try {
            LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
            if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(local, BukkitAdapter.adapt(at.getWorld()))) {
                return true;
            }
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            return query.testState(BukkitAdapter.adapt(at), local, Flags.BUILD);
        } catch (Throwable t) {
            if (logger != null) {
                logger.warning("WorldGuard build check failed: " + t.getMessage());
            }
            return true;
        }
    }

    public static boolean isCenterProtectStone(Location at) {
        if (at == null || at.getWorld() == null) {
            return false;
        }
        return isProtectStone(at.getBlock());
    }

    public static boolean volumeContainsProtectStone(World world, int minX, int minY, int minZ,
                                                    int maxX, int maxY, int maxZ) {
        if (!protectPsBlocks || world == null || psIsProtectBlock == null) {
            return false;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isProtectStone(world, x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
