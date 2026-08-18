package im.hinexx.itemsPlugin.util;

import im.hinexx.itemsPlugin.ItemsPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

public final class CooldownService {

    public enum Type {
        AFTER_MISSING,
        AFTER_USE
    }

    private final ItemsPlugin plugin;

    public CooldownService(ItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("cooldowns.enable", true);
    }

    public Type type() {
        String raw = plugin.getConfig().getString("cooldowns.type", "after_missing");
        if (raw == null) {
            return Type.AFTER_MISSING;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "after_use", "after_using", "use", "on_use" -> Type.AFTER_USE;
            default -> Type.AFTER_MISSING;
        };
    }

    public boolean applyOnUse() {
        return enabled() && type() == Type.AFTER_USE;
    }

    public boolean applyOnEnd() {
        return enabled() && type() == Type.AFTER_MISSING;
    }

    public int seconds(String itemId, int def) {
        if (!enabled()) {
            return 0;
        }
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("cooldowns");
        if (section == null || itemId == null) {
            return def;
        }
        String key = itemId.toLowerCase(Locale.ROOT);
        if (!section.contains(key)) {
            return def;
        }
        return ItemsService.parseSeconds(section.get(key), def);
    }
}
