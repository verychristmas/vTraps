package im.hinexx.itemsPlugin.Items;

import im.hinexx.itemsPlugin.ItemsPlugin;
import im.hinexx.itemsPlugin.util.CustomItems;
import im.hinexx.itemsPlugin.util.EffectParser;
import im.hinexx.itemsPlugin.util.ItemUseHelper;
import im.hinexx.itemsPlugin.util.Messages;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class Dizorent implements Listener {

    private static final String ITEM_ID = CustomItems.ID_DIZORENT;
    private static final double RADIUS = 9;
    private static final int RAYS = 36;
    private static final int BURST_TICKS = 8;
    private static final double PARTICLE_SPEED = 0.55;
    private static final double FOOT_HEIGHT = 0.15;

    private final ItemsPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<BukkitTask> tasks = new HashSet<>();
    private final AtomicInteger activeEffects = new AtomicInteger();
    private List<EffectParser.ParsedEffect> cachedEffects = List.of();

    public Dizorent(ItemsPlugin plugin) {
        this.plugin = plugin;
        reloadFromConfig();
    }

    public static ItemStack createItem() {
        return CustomItems.createFromId(ITEM_ID);
    }

    public void reloadFromConfig() {
        if (!plugin.getConfig().getBoolean("dizorent.effect", true)) {
            cachedEffects = List.of();
            return;
        }
        List<String> raw = plugin.getConfig().getStringList("dizorent.effects");
        cachedEffects = EffectParser.parseAll(raw, plugin.getLogger());
    }

    public void invalidateCache() {
        reloadFromConfig();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ItemUseHelper.clearPlayer(cooldowns, event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
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
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        if (!plugin.getConfig().getBoolean("dizorent.enable", true)) {
            return;
        }

        if (ItemUseHelper.isOnCooldown(cooldowns, player)) {
            long left = ItemUseHelper.cooldownSecondsLeft(cooldowns, player);
            plugin.messages().send(player, "common.cooldown", Messages.placeholders("seconds", String.valueOf(left)));
            return;
        }

        int max = plugin.getConfig().getInt("performance.max-concurrent-dizorent", 15);
        if (activeEffects.get() >= max) {
            plugin.messages().send(player, "common.busy");
            return;
        }

        int cooldown = plugin.cooldowns().seconds(ITEM_ID, 5);
        Material cooldownMat = item.getType();
        ItemUseHelper.consumeOne(player, item);
        if (plugin.cooldowns().applyOnUse()) {
            ItemUseHelper.setCooldown(cooldowns, player, cooldown, cooldownMat);
        }

        applyEffectsNearby(player, RADIUS);
        playEndRodBurst(player, RADIUS, cooldown, cooldownMat);
        plugin.messages().send(player, "dizorent.activated");
    }

    private void applyEffectsNearby(Player caster, double radius) {
        if (cachedEffects.isEmpty()) {
            return;
        }
        Location src = caster.getLocation();
        for (org.bukkit.entity.Entity entity : caster.getWorld().getNearbyEntities(src, radius, radius, radius,
                e -> e instanceof Player && e != caster)) {
            EffectParser.apply((Player) entity, cachedEffects);
        }
    }

    private void playEndRodBurst(Player caster, double maxRadius, int cooldownSeconds, Material cooldownMat) {
        World world = caster.getWorld();
        if (world == null) {
            return;
        }

        Location origin = caster.getLocation().clone();
        origin.setY(origin.getY() + FOOT_HEIGHT);
        world.playSound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.6f);
        world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.35f, 1.8f);

        Vector[] dirs = new Vector[RAYS];
        for (int i = 0; i < RAYS; i++) {
            double angle = (Math.PI * 2.0 * i) / RAYS;
            dirs[i] = new Vector(Math.cos(angle), 0, Math.sin(angle));
        }

        UUID ownerId = caster.getUniqueId();
        activeEffects.incrementAndGet();
        track(new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!caster.isOnline() || tick >= BURST_TICKS) {
                    activeEffects.updateAndGet(v -> Math.max(0, v - 1));
                    if (plugin.cooldowns().applyOnEnd()) {
                        ItemUseHelper.startCooldownIfOnline(cooldowns, ownerId, cooldownSeconds, cooldownMat);
                    }
                    cancel();
                    return;
                }

                double progress = (tick + 1.0) / BURST_TICKS;
                double distance = progress * maxRadius;

                Location base = caster.isOnline() ? caster.getLocation().clone() : origin.clone();
                base.setY(base.getY() + FOOT_HEIGHT);

                for (Vector dir : dirs) {
                    Location at = base.clone().add(dir.getX() * distance, 0, dir.getZ() * distance);
                    world.spawnParticle(
                            Particle.END_ROD,
                            at,
                            0,
                            dir.getX(),
                            0,
                            dir.getZ(),
                            PARTICLE_SPEED
                    );
                    if (tick % 2 == 0) {
                        double back = Math.max(0, distance - 0.6);
                        Location trail = base.clone().add(dir.getX() * back, 0, dir.getZ() * back);
                        world.spawnParticle(Particle.END_ROD, trail, 1, 0.08, 0.02, 0.08, 0);
                    }
                }

                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L));
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
        activeEffects.set(0);
        ItemUseHelper.purgeExpired(cooldowns);
    }
}
