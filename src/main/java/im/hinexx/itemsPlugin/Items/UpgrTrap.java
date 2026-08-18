package im.hinexx.itemsPlugin.Items;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import im.hinexx.itemsPlugin.ItemsPlugin;
import im.hinexx.itemsPlugin.util.ActiveTrapZones;
import im.hinexx.itemsPlugin.util.CustomItems;
import im.hinexx.itemsPlugin.util.EffectParser;
import im.hinexx.itemsPlugin.util.ItemUseHelper;
import im.hinexx.itemsPlugin.util.Messages;
import im.hinexx.itemsPlugin.util.ProtectionHook;
import im.hinexx.itemsPlugin.util.TrapRoofFiller;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class UpgrTrap implements Listener {

    private static final String ITEM_ID = CustomItems.ID_UPGR_TRAP;
    private static final double PULL_STRENGTH = 0.45;
    private static final double COLLISION_HORIZONTAL = 1.15;
    private static final int MIN_PULL_TICKS = 14;
    private static final int DIG_HALF = 3;
    private static final int DIG_DEPTH = 5;
    private static final int ROOF_HALF = 3;
    private static final int SAVE_HALF = 6;
    private static final int PULL_TIMEOUT = 200;

    private final ItemsPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> pulling = new HashSet<>();
    private final Map<String, TrapData> activeTraps = new HashMap<>();
    private final Set<BukkitTask> tasks = new HashSet<>();
    private final AtomicInteger activeCount = new AtomicInteger();

    public UpgrTrap(ItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public static ItemStack createItem() {
        return CustomItems.createFromId(ITEM_ID);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        ItemUseHelper.clearPlayer(cooldowns, id);
        pulling.remove(id);
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

        if (pulling.contains(player.getUniqueId())) {
            plugin.messages().send(player, "upgrtrap.already-active");
            return;
        }

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

        double radius = plugin.items().useRadius(ITEM_ID, 16);
        Player target = findNearestPlayer(player, radius);
        if (target == null) {
            plugin.messages().send(player, "upgrtrap.no-target");
            return;
        }

        ItemStack consumed = item.clone();
        consumed.setAmount(1);
        Material cooldownMat = item.getType();
        ItemUseHelper.consumeOne(player, item);
        if (plugin.cooldowns().applyOnUse()) {
            ItemUseHelper.setCooldown(cooldowns, player, plugin.cooldowns().seconds(ITEM_ID, 5), cooldownMat);
        }
        pulling.add(player.getUniqueId());
        activateTrap(player, target, consumed, cooldownMat);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChorusTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            return;
        }
        if (activeTraps.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        for (TrapData trap : activeTraps.values()) {
            if (trap.location == null || trap.owner == null) {
                continue;
            }
            boolean fromInside = contains(trap, from);
            boolean toInside = contains(trap, to);
            if (!fromInside && !toInside) {
                continue;
            }

            boolean isOwner = player.getUniqueId().equals(trap.owner);
            if (isOwner && trap.chorusOwnerAllowed) {
                return;
            }

            event.setCancelled(true);
            plugin.messages().send(player, "upgrtrap.chorus-blocked");
            return;
        }
    }

    private static boolean contains(TrapData trap, Location loc) {
        if (loc == null || loc.getWorld() == null || trap.location == null) {
            return false;
        }
        if (!loc.getWorld().equals(trap.location.getWorld())) {
            return false;
        }
        int dx = Math.abs(loc.getBlockX() - trap.location.getBlockX());
        int dz = Math.abs(loc.getBlockZ() - trap.location.getBlockZ());
        int y = loc.getBlockY();
        int ground = trap.roofY;
        return dx <= DIG_HALF && dz <= DIG_HALF && y >= ground - DIG_DEPTH && y <= ground;
    }

    private Player findNearestPlayer(Player source, double radius) {
        Player nearest = null;
        double minDistanceSq = radius * radius;
        Location src = source.getLocation();

        for (Entity entity : source.getWorld().getNearbyEntities(src, radius, radius, radius,
                e -> e instanceof Player && e != source)) {
            Player target = (Player) entity;
            double distSq = src.distanceSquared(target.getLocation());
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq;
                nearest = target;
            }
        }
        return nearest;
    }

    private void activateTrap(Player caster, Player target, ItemStack consumedItem, Material cooldownMat) {
        plugin.messages().send(caster, "upgrtrap.activated");
        plugin.messages().send(target, "upgrtrap.pulled");

        int pullStep = Math.max(1, plugin.getConfig().getInt("performance.pull-tick-step", 2));

        track(new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!caster.isOnline() || !target.isOnline()) {
                    failPull(caster, consumedItem);
                    cancel();
                    return;
                }

                Location casterLoc = caster.getLocation();
                Location targetLoc = target.getLocation();
                double dx = casterLoc.getX() - targetLoc.getX();
                double dz = casterLoc.getZ() - targetLoc.getZ();
                double horizontal = Math.sqrt(dx * dx + dz * dz);

                if (ticks >= MIN_PULL_TICKS && horizontal <= COLLISION_HORIZONTAL) {
                    cancel();
                    pulling.remove(caster.getUniqueId());
                    createTrapAndPush(caster, target, cooldownMat);
                    return;
                }

                if (ticks >= PULL_TIMEOUT) {
                    failPull(caster, consumedItem);
                    cancel();
                    return;
                }

                Vector direction = casterLoc.toVector().subtract(targetLoc.toVector());
                if (direction.lengthSquared() < 1.0E-6) {
                    target.setVelocity(new Vector(0, 0.55, 0));
                    caster.setVelocity(new Vector(0, 0.25, 0));
                    ticks += pullStep;
                    return;
                }
                direction.normalize();

                double distance = casterLoc.distance(targetLoc);
                double verticalOffset;
                if (ticks < MIN_PULL_TICKS) {
                    verticalOffset = 0.45;
                } else if (distance < 3) {
                    verticalOffset = -0.15;
                } else {
                    verticalOffset = 0.05;
                }

                Vector velocity = direction.multiply(PULL_STRENGTH);
                velocity.setY(velocity.getY() + verticalOffset);
                target.setVelocity(velocity);

                if (ticks % 6 == 0) {
                    target.getWorld().spawnParticle(Particle.CRIT, targetLoc, 4, 0.2, 0.3, 0.2, 0.05);
                }
                ticks += pullStep;
            }
        }.runTaskTimer(plugin, 0L, pullStep));
    }

    private void failPull(Player caster, ItemStack consumedItem) {
        pulling.remove(caster.getUniqueId());
        if (caster.isOnline()) {
            ItemUseHelper.giveOrDrop(caster, consumedItem);
            plugin.messages().send(caster, "upgrtrap.failed");
        }
    }

    private void createTrapAndPush(Player caster, Player target, Material cooldownMat) {
        Location feet = caster.getLocation().clone();
        World world = feet.getWorld();
        if (world == null) {
            return;
        }
        int cx = feet.getBlockX();
        int cz = feet.getBlockZ();
        int groundY = TrapRoofFiller.groundY(feet);
        Location trapCenter = new Location(world, cx + 0.5, groundY - 3, cz + 0.5);

        TrapData trapData = new TrapData();
        trapData.owner = caster.getUniqueId();
        trapData.chorusOwnerAllowed = plugin.items().chorusOwner(ITEM_ID);
        trapData.roofY = groundY;
        trapData.roofHalf = ROOF_HALF;
        trapData.centerX = cx;
        trapData.centerZ = cz;
        trapData.zoneId = key(trapCenter);
        trapData.cooldownMat = cooldownMat;
        trapData.cooldownSeconds = plugin.cooldowns().seconds(ITEM_ID, 5);

        trapData.roofBlocks = TrapRoofFiller.sampleRoof(world, cx, groundY, cz, ROOF_HALF);
        saveOriginalBlocks(trapCenter, trapData);

        world.playSound(trapCenter, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.6f);
        world.spawnParticle(Particle.EXPLOSION, trapCenter.clone().add(0, 2, 0), 8, 2.0, 1.5, 2.0, 0.1);

        TrapRoofFiller.digShaft(world, cx, groundY, cz, DIG_HALF, DIG_DEPTH + 1);
        pasteConfigured(world, cx, cz, groundY);

        activeTraps.put(trapData.zoneId, trapData);
        activeCount.incrementAndGet();
        ActiveTrapZones.register(ActiveTrapZones.Zone.cuboid(
                trapData.zoneId, world, cx, groundY, cz,
                DIG_HALF, DIG_DEPTH, 1, DIG_HALF, trapData.owner));

        Location drop = new Location(world, cx + 0.5, groundY - 1.2, cz + 0.5, caster.getYaw(), caster.getPitch());
        caster.teleport(drop);
        Location targetDrop = drop.clone();
        targetDrop.setYaw(target.getYaw());
        targetDrop.setPitch(target.getPitch());
        target.teleport(targetDrop);
        caster.setVelocity(new Vector(0, -1.2, 0));
        target.setVelocity(new Vector(0, -1.2, 0));

        track(new BukkitRunnable() {
            @Override
            public void run() {
                TrapRoofFiller.seal(world, cx, groundY, cz, ROOF_HALF, trapData.roofBlocks);
                world.playSound(new Location(world, cx + 0.5, groundY, cz + 0.5), Sound.BLOCK_GLASS_PLACE, 1.2f, 0.8f);
                int duration = plugin.items().trapDurationSeconds(ITEM_ID, 12);

                track(new BukkitRunnable() {
                    @Override
                    public void run() {
                        openRoofAndEject(trapCenter, caster, target);
                    }
                }.runTaskLater(plugin, duration * 20L));
            }
        }.runTaskLater(plugin, 4L));

        applyEffectsToVictims(trapCenter, caster.getUniqueId(), target);
    }

    private void applyEffectsToVictims(Location center, UUID ownerId, Player primaryTarget) {
        var effects = plugin.items().effects(ITEM_ID);
        if (effects.isEmpty()) {
            return;
        }
        Set<UUID> applied = new HashSet<>();
        if (primaryTarget != null && primaryTarget.isOnline()
                && !primaryTarget.getUniqueId().equals(ownerId)) {
            EffectParser.apply(primaryTarget, effects);
            applied.add(primaryTarget.getUniqueId());
        }

        World world = center.getWorld();
        if (world == null) {
            return;
        }
        double pad = DIG_HALF + 0.5;
        for (Entity entity : world.getNearbyEntities(center, pad, DIG_DEPTH + 2, pad, e -> e instanceof Player)) {
            Player p = (Player) entity;
            if (p.getUniqueId().equals(ownerId)) {
                continue;
            }
            if (applied.add(p.getUniqueId())) {
                EffectParser.apply(p, effects);
            }
        }
    }

    private void saveOriginalBlocks(Location center, TrapData trapData) {
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(center.getWorld()))) {
            editSession.setFastMode(true);
            int y = trapData.roofY;
            BlockVector3 min = BlockVector3.at(
                    trapData.centerX - SAVE_HALF,
                    y - DIG_DEPTH - 2,
                    trapData.centerZ - SAVE_HALF
            );
            BlockVector3 max = BlockVector3.at(
                    trapData.centerX + SAVE_HALF,
                    y + 2,
                    trapData.centerZ + SAVE_HALF
            );
            CuboidRegion region = new CuboidRegion(min, max);
            trapData.originalClipboard = new BlockArrayClipboard(region);

            ForwardExtentCopy copy = new ForwardExtentCopy(editSession, region, trapData.originalClipboard, min);
            copy.setCopyingEntities(false);
            Operations.complete(copy);
            trapData.location = center.clone();
            trapData.saveMin = min;
        } catch (WorldEditException e) {
            plugin.getLogger().severe("UpgrTrap save error: " + e.getMessage());
        }
    }

    private void pasteConfigured(World world, int digCx, int digCz, int groundY) {
        File schematicFile = plugin.structures().resolveConfigured("upgr-trap");
        if (schematicFile == null || !schematicFile.isFile()) {
            schematicFile = plugin.structures().resolveConfigured("explode-trap");
        }
        if (schematicFile == null || !schematicFile.isFile()) {
            plugin.getLogger().warning("upgr-trap structure missing: " + plugin.structures().configuredStructure("upgr-trap")
                    + " (ожидался файл в plugins/vTraps/structures/)");
            return;
        }
        plugin.schematics().pasteTrapAligned(world, digCx, digCz, groundY, schematicFile);
    }

    private void openRoofAndEject(Location center, Player caster, Player target) {
        World world = center.getWorld();
        TrapData data = activeTraps.get(key(center));
        int cx = data != null ? data.centerX : center.getBlockX();
        int cz = data != null ? data.centerZ : center.getBlockZ();
        int groundY = data != null ? data.roofY : center.getBlockY() + 3;
        int half = data != null ? Math.max(data.roofHalf, DIG_HALF) : ROOF_HALF;

        if (world != null) {
            TrapRoofFiller.openExit(world, cx, groundY, cz, half);
        }

        Location exit = TrapRoofFiller.findSafeExit(world, cx, cz, groundY, 0f, 0f);
        TrapRoofFiller.ejectPlayer(caster, exit);
        TrapRoofFiller.ejectPlayer(target, exit);

        if (world != null && exit != null) {
            world.playSound(exit, Sound.BLOCK_GLASS_BREAK, 1.2f, 1.2f);
            world.spawnParticle(Particle.CLOUD, exit, 20, 2.5, 0.4, 2.5, 0.05);
        }

        final int ejectCx = cx;
        final int ejectCz = cz;
        final int ejectGy = groundY;
        track(new BukkitRunnable() {
            @Override
            public void run() {
                removeTrap(center, caster);
                TrapRoofFiller.unstuckIfNeeded(caster, ejectCx, ejectCz, ejectGy);
                TrapRoofFiller.unstuckIfNeeded(target, ejectCx, ejectCz, ejectGy);
            }
        }.runTaskLater(plugin, 8L));
    }

    private void track(BukkitTask task) {
        tasks.removeIf(BukkitTask::isCancelled);
        tasks.add(task);
    }

    private void removeTrap(Location location, Player player) {
        TrapData trapData = activeTraps.remove(key(location));
        if (trapData == null) {
            return;
        }

        if (trapData.originalClipboard != null && trapData.saveMin != null) {
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(location.getWorld()))) {
                editSession.setFastMode(true);
                Operation operation = new ClipboardHolder(trapData.originalClipboard)
                        .createPaste(editSession)
                        .to(trapData.saveMin)
                        .ignoreAirBlocks(false)
                        .build();
                Operations.complete(operation);
            } catch (WorldEditException e) {
                plugin.getLogger().severe("UpgrTrap restore error: " + e.getMessage());
            }
        }

        boolean last = trapData.zoneId != null && ActiveTrapZones.unregister(trapData.zoneId);
        if (last && trapData.owner != null && plugin.cooldowns().applyOnEnd()) {
            ItemUseHelper.startCooldownIfOnline(
                    cooldowns, trapData.owner, trapData.cooldownSeconds, trapData.cooldownMat);
        }

        activeCount.updateAndGet(v -> Math.max(0, v - 1));
        location.getWorld().playSound(location, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.0f);

        if (player != null && player.isOnline()) {
            plugin.messages().send(player, "upgrtrap.expired");
        }
    }

    public void cleanup() {
        for (BukkitTask task : Set.copyOf(tasks)) {
            task.cancel();
        }
        tasks.clear();
        pulling.clear();

        for (Map.Entry<String, TrapData> entry : Map.copyOf(activeTraps).entrySet()) {
            String[] parts = entry.getKey().split(":");
            if (parts.length != 4 || plugin.getServer().getWorld(parts[0]) == null) {
                continue;
            }
            Location loc = new Location(
                    plugin.getServer().getWorld(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3])
            );
            removeTrap(loc, null);
        }
        activeTraps.clear();
        activeCount.set(0);
        ItemUseHelper.purgeExpired(cooldowns);
    }

    private static String key(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private static class TrapData {
        Clipboard originalClipboard;
        Location location;
        BlockVector3 saveMin;
        UUID owner;
        boolean chorusOwnerAllowed;
        BlockData[][] roofBlocks;
        int roofY;
        int roofHalf;
        int centerX;
        int centerZ;
        String zoneId;
        Material cooldownMat;
        int cooldownSeconds;
    }
}
