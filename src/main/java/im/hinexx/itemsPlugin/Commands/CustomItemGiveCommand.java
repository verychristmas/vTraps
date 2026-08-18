package im.hinexx.itemsPlugin.Commands;

import im.hinexx.itemsPlugin.ItemsPlugin;
import im.hinexx.itemsPlugin.util.CustomItems;
import im.hinexx.itemsPlugin.util.ItemsService;
import im.hinexx.itemsPlugin.util.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CustomItemGiveCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION_GIVE = "vtraps.give";
    private static final String PERMISSION_RELOAD = "vtraps.reload";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = ItemsPlugin.getInstance().messages();

        if (args.length == 0) {
            messages.send(sender, "command.usage");
            messages.send(sender, "command.usage-reload");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission(PERMISSION_RELOAD) && !sender.hasPermission(PERMISSION_GIVE)) {
                messages.send(sender, "command.no-permission");
                return true;
            }
            ItemsPlugin.getInstance().reloadPlugin();
            messages.send(sender, "command.reload-success");
            return true;
        }

        if (!sub.equals("give")) {
            messages.send(sender, "command.usage");
            messages.send(sender, "command.usage-reload");
            return true;
        }

        if (!sender.hasPermission(PERMISSION_GIVE)) {
            messages.send(sender, "command.no-permission");
            return true;
        }

        List<String> available = availableItemNames();
        if (args.length < 3) {
            messages.send(sender, "command.usage");
            messages.send(sender, "command.available-items", Messages.placeholders("items", String.join(", ", available)));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "command.player-not-found");
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                if (amount <= 0 || amount > 64) {
                    messages.send(sender, "command.amount-range");
                    return true;
                }
            } catch (NumberFormatException e) {
                messages.send(sender, "command.invalid-amount");
                return true;
            }
        }

        ItemStack item = CustomItems.createFromId(args[2]);
        if (item == null) {
            messages.send(sender, "command.unknown-item", Messages.placeholders("item", args[2]));
            messages.send(sender, "command.available-items", Messages.placeholders("items", String.join(", ", available)));
            return true;
        }

        item.setAmount(amount);
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(item);
        leftover.values().forEach(stack -> target.getWorld().dropItemNaturally(target.getLocation(), stack));

        messages.send(sender, "command.given-sender", Messages.placeholders(
                "item", args[2].toLowerCase(Locale.ROOT),
                "amount", String.valueOf(amount),
                "player", target.getName()
        ));

        var display = item.getItemMeta() != null && item.getItemMeta().displayName() != null
                ? item.getItemMeta().displayName()
                : messages.get("command.unknown-item", Messages.placeholders("item", args[2]));

        messages.send(target, "command.given-target",
                Placeholder.component("item_name", display),
                Placeholder.parsed("amount", String.valueOf(amount))
        );
        return true;
    }

    private List<String> availableItemNames() {
        ItemsService items = ItemsPlugin.getInstance().items();
        if (items == null || items.ids().isEmpty()) {
            return List.of();
        }
        return items.ids();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        boolean canGive = sender.hasPermission(PERMISSION_GIVE);
        boolean canReload = sender.hasPermission(PERMISSION_RELOAD) || canGive;
        if (!canGive && !canReload) {
            return completions;
        }

        // /vtraps <give|reload>
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if (canGive && "give".startsWith(prefix)) {
                completions.add("give");
            }
            if (canReload && "reload".startsWith(prefix)) {
                completions.add("reload");
            }
            return completions;
        }

        if (!canGive || !args[0].equalsIgnoreCase("give")) {
            return completions;
        }

        // /vtraps give <player>
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    completions.add(player.getName());
                }
            }
            return completions;
        }

        // /vtraps give <player> <item>
        if (args.length == 3) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (String item : availableItemNames()) {
                if (item.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    completions.add(item);
                }
            }
            return completions;
        }

        // /vtraps give <player> <item> <amount>
        if (args.length == 4) {
            completions.addAll(Arrays.asList("1", "8", "16", "32", "64"));
        }
        return completions;
    }
}
