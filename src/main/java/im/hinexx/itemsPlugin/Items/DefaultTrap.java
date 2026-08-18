package im.hinexx.itemsPlugin.Items;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import im.hinexx.itemsPlugin.ItemsPlugin;
import im.hinexx.itemsPlugin.util.ActiveTrapZones;
import im.hinexx.itemsPlugin.util.CustomItems;
import im.hinexx.itemsPlugin.util.ItemUseHelper;
import im.hinexx.itemsPlugin.util.Messages;
import im.hinexx.itemsPlugin.util.ProtectionHook;
import im.hinexx.itemsPlugin.util.SchematicCache;
import im.hinexx.itemsPlugin.util.TrapBlockLedger;
import im.hinexx.itemsPlugin.util.TrapRoofFiller;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultTrap implements Listener {

    private static final String ITEM_ID = CustomItems.ID_DEFAULT;

    private final ItemsPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<String, TrapData> activeTraps = new HashMap<>();
    private final Set<BukkitTask> tasks = new HashSet<>();
    private final AtomicInteger activeCount = new AtomicInteger();

    public DefaultTrap(ItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public static ItemStack createItem() {
        return CustomItems.createFromId(ITEM_ID);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ItemUseHelper.clearPlayer(cooldowns, event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!CustomItems.is(item, ITEM_ID)) {
            return;
        }
        if (!ItemUseHelper.claimInteractThisTick(player)) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);

        if (ItemUseHelper.isOnCooldown(cooldowns, player)) {
            long left = ItemUseHelper.cooldownSecondsLeft(cooldowns, player);
            plugin.messages().send(player, "common.cooldown", Messages.placeholders("seconds", String.valueOf(left)));
            return;
        }

        int max = plugin.getConfig().getInt("performance.max-concurrent-traps", 20);
        if (activeCount.get() >= max) {
            plugin.messages().send(player, "common.busy");
            return;
        }

        if (!ProtectionHook.canUseTrapHere(player, player.getLocation())) {
            plugin.messages().send(player, "protection.denied");
            return;
        }

        if (ActiveTrapZones.cannotStartTrap(player, plugin.items().allowUseInTrap())) {
            plugin.messages().send(player, "common.in-trap");
            return;
        }

        File schem = plugin.structures().resolveConfigured("default-trap");
        if (schem == null || !schem.isFile()) {
            plugin.getLogger().warning("default-trap structure missing: "
                    + plugin.structures().configuredStructure("default-trap")
                    + " (положи default.schem в plugins/vTraps/structures/)");
            plugin.messages().send(player, "defaulttrap.missing-schem");
            return;
        }

        Material cooldownMat = item.getType();
        ItemUseHelper.consumeOne(player, item);
        if (plugin.cooldowns().applyOnUse()) {
            ItemUseHelper.setCooldown(cooldowns, player, plugin.cooldowns().seconds(ITEM_ID, 5), cooldownMat);
        }

        activate(player, schem, cooldownMat);
    }

    private void activate(Player caster, File schem, Material cooldownMat) {
        plugin.messages().send(caster, "defaulttrap.activated");

        Location feet = caster.getLocation();
        World world = feet.getWorld();
        if (world == null) {
            return;
        }

        String trapId = UUID.randomUUID().toString();
        int px = feet.getBlockX();
        int pz = feet.getBlockZ();
        int groundY = TrapRoofFiller.groundY(feet);

        SchematicCache.CagePrep prep = plugin.schematics().prepareCenteredCage(world, px, groundY, pz, schem, trapId);
        if (prep.isEmpty()) {
            plugin.getLogger().warning("default.schem empty or unloadable");
            plugin.messages().send(caster, "defaulttrap.missing-schem");
            return;
        }

        Location stand = new Location(world, prep.standX() + 0.5, prep.standY(), prep.standZ() + 0.5,
                caster.getYaw(), caster.getPitch());

        TrapData data = new TrapData();
        data.trapId = trapId;
        data.worldName = world.getName();
        data.volume = Set.copyOf(prep.originals().keySet());
        data.originalBlocks = prep.originals();
        data.centerX = prep.standX();
        data.centerY = prep.standY();
        data.centerZ = prep.standZ();
        data.owner = caster.getUniqueId();
        data.cooldownMat = cooldownMat;
        data.cooldownSeconds = plugin.cooldowns().seconds(ITEM_ID, 5);
        activeTraps.put(trapId, data);
        activeCount.incrementAndGet();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockVector3 v : data.volume) {
            minX = Math.min(minX, v.x());
            minY = Math.min(minY, v.y());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxY = Math.max(maxY, v.y());
            maxZ = Math.max(maxZ, v.z());
        }
        ActiveTrapZones.register(ActiveTrapZones.Zone.fromVolume(
                trapId, world, minX, minY, minZ, maxX, maxY, maxZ, data.owner));

        caster.teleport(stand);
        caster.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        caster.setFallDistance(0f);

        for (Player other : playersStandingInVolume(world, data.volume, caster.getUniqueId())) {
            plugin.messages().send(other, "defaulttrap.caught");
        }

        List<SchematicCache.AnimBlock> blocks = prep.solids();
        double buildSeconds = plugin.getConfig().getDouble("build-default-trap.build-seconds", 0.65);
        final int durationTicks = Math.max(1, (int) Math.round(buildSeconds * 20.0));
        final int totalBlocks = blocks.size();

        track(new BukkitRunnable() {
            int tick;

            @Override
            public void run() {
                if (!activeTraps.containsKey(trapId)) {
                    cancel();
                    return;
                }
                int from = (int) ((long) tick * totalBlocks / durationTicks);
                int to = (int) ((long) (tick + 1) * totalBlocks / durationTicks);
                if (to > from) {
                    plugin.schematics().placeAnimBatch(world, blocks, from, to - from);
                    world.playSound(stand, Sound.BLOCK_GLASS_PLACE, 0.35f, 1.2f + (tick % 6) * 0.05f);
                    if (tick % 2 == 0) {
                        world.spawnParticle(Particle.CLOUD, stand.clone().add(0, 1.0, 0), 2, 0.6, 0.4, 0.6, 0.01);
                    }
                }
                tick++;
                if (tick >= durationTicks) {
                    cancel();
                    finishBuild(trapId, stand, caster);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L));
    }

    private void finishBuild(String trapId, Location stand, Player caster) {
        World world = stand.getWorld();
        if (world != null) {
            world.playSound(stand, Sound.BLOCK_GLASS_PLACE, 1.0f, 0.9f);
            world.spawnParticle(Particle.CLOUD, stand.clone().add(0, 1.2, 0), 14, 1.2, 0.6, 1.2, 0.02);
        }

        int duration = plugin.items().trapDurationSeconds(ITEM_ID, 12);
        track(new BukkitRunnable() {
            @Override
            public void run() {
                removeTrap(trapId, caster);
            }
        }.runTaskLater(plugin, duration * 20L));
    }

    private static List<Player> playersStandingInVolume(World world, Set<BlockVector3> volume, UUID exclude) {
        if (world == null || volume == null || volume.isEmpty()) {
            return List.of();
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockVector3 v : volume) {
            minX = Math.min(minX, v.x());
            minY = Math.min(minY, v.y());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxY = Math.max(maxY, v.y());
            maxZ = Math.max(maxZ, v.z());
        }
        double cx = (minX + maxX) / 2.0 + 0.5;
        double cy = (minY + maxY) / 2.0 + 0.5;
        double cz = (minZ + maxZ) / 2.0 + 0.5;
        double hx = (maxX - minX) / 2.0 + 1.0;
        double hy = (maxY - minY) / 2.0 + 1.0;
        double hz = (maxZ - minZ) / 2.0 + 1.0;

        List<Player> found = new java.util.ArrayList<>();
        for (Entity e : world.getNearbyEntities(new Location(world, cx, cy, cz), hx, hy, hz, ent -> ent instanceof Player)) {
            Player p = (Player) e;
            if (exclude != null && p.getUniqueId().equals(exclude)) {
                continue;
            }
            int x = p.getLocation().getBlockX();
            int y = p.getLocation().getBlockY();
            int z = p.getLocation().getBlockZ();
            if (x >= minX && x <= maxX && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ) {
                found.add(p);
            }
        }
        return found;
    }

    private void removeTrap(String trapId, Player player) {
        TrapData trapData = activeTraps.remove(trapId);
        if (trapData == null) {
            return;
        }

        World world = null;
        if (trapData.worldName != null) {
            world = plugin.getServer().getWorld(trapData.worldName);
        }
        if (world == null && player != null && player.isOnline()) {
            world = player.getWorld();
        }

        if (world != null && trapData.volume != null && !trapData.volume.isEmpty()) {
            TrapBlockLedger.popTrap(world, trapId, trapData.volume);
        }

        boolean last = ActiveTrapZones.unregister(trapId);
        if (last && trapData.owner != null && plugin.cooldowns().applyOnEnd()) {
            ItemUseHelper.startCooldownIfOnline(
                    cooldowns, trapData.owner, trapData.cooldownSeconds, trapData.cooldownMat);
        }

        activeCount.updateAndGet(v -> Math.max(0, v - 1));
        if (world != null) {
            Location fx = new Location(world, trapData.centerX + 0.5, trapData.centerY, trapData.centerZ + 0.5);
            world.playSound(fx, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.15f);
            world.spawnParticle(Particle.CLOUD, fx.clone().add(0, 1.0, 0), 16, 1.4, 0.7, 1.4, 0.02);
        }

        if (player != null && player.isOnline()) {
            plugin.messages().send(player, "defaulttrap.expired");
        }
    }

    private void track(BukkitTask task) {
        tasks.removeIf(BukkitTask::isCancelled);
        tasks.add(task);
    }

    public void cleanup() {
        for (BukkitTask task : Set.copyOf(tasks)) {
            task.cancel();
        }
        tasks.clear();
        for (String trapId : Set.copyOf(activeTraps.keySet())) {
            removeTrap(trapId, null);
        }
        activeTraps.clear();
        activeCount.set(0);
        ItemUseHelper.purgeExpired(cooldowns);
    }

    private static class TrapData {
        String trapId;
        String worldName;
        Set<BlockVector3> volume = Set.of();
        Map<BlockVector3, BlockState> originalBlocks = Map.of();
        int centerX;
        int centerY;
        int centerZ;
        UUID owner;
        Material cooldownMat;
        int cooldownSeconds;
    }
}
