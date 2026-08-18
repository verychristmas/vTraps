package im.hinexx.itemsPlugin.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemUseHelper {

    private static final Map<UUID, Integer> LAST_INTERACT_TICK = new ConcurrentHashMap<>();

    private ItemUseHelper() {
    }

    public static boolean isRightClickMainHand(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return false;
        }
        Action action = event.getAction();
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    public static boolean claimInteractThisTick(Player player) {
        UUID id = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();
        Integer last = LAST_INTERACT_TICK.put(id, tick);
        return last == null || last != tick;
    }

    public static boolean isOnCooldown(Map<UUID, Long> cooldowns, Player player) {
        Long until = cooldowns.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public static long cooldownSecondsLeft(Map<UUID, Long> cooldowns, Player player) {
        Long until = cooldowns.get(player.getUniqueId());
        if (until == null) {
            return 0;
        }
        return Math.max(0L, (until - System.currentTimeMillis() + 999L) / 1000L);
    }

    public static void setCooldown(Map<UUID, Long> cooldowns, Player player, int seconds, Material material) {
        setCooldown(cooldowns, player.getUniqueId(), seconds);
        if (seconds > 0 && material != null) {
            player.setCooldown(material, seconds * 20);
        }
    }

    public static void setCooldown(Map<UUID, Long> cooldowns, UUID id, int seconds) {
        cooldowns.put(id, System.currentTimeMillis() + Math.max(0, seconds) * 1000L);
    }

    public static void startCooldownIfOnline(Map<UUID, Long> cooldowns, UUID id, int seconds, Material material) {
        setCooldown(cooldowns, id, seconds);
        if (seconds <= 0 || material == null) {
            return;
        }
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline()) {
            player.setCooldown(material, seconds * 20);
        }
    }

    public static void clearPlayer(Map<UUID, Long> cooldowns, UUID id) {
        cooldowns.remove(id);
        LAST_INTERACT_TICK.remove(id);
    }

    public static void purgeExpired(Map<UUID, Long> cooldowns) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = cooldowns.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    public static void consumeOne(Player player, ItemStack item) {
        if (player == null || item == null) {
            return;
        }
        // Always mutate the live main-hand stack to avoid ghost references / offhand races.
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            return;
        }
        if (hand != item) {
            String id = CustomItems.readId(item);
            if (id == null || !CustomItems.is(hand, id)) {
                return;
            }
        }
        int amount = hand.getAmount();
        if (amount > 1) {
            hand.setAmount(amount - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    public static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        leftover.values().forEach(stack ->
                player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }
}
