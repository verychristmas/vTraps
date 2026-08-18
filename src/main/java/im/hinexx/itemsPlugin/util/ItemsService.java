package im.hinexx.itemsPlugin.util;

import im.hinexx.itemsPlugin.ItemsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ItemsService {

    public record ItemEntry(
            String id,
            Material material,
            String displayNameRaw,
            List<String> loreRaw,
            List<String> effectsRaw,
            boolean chorusOwner
    ) {
    }

    private final ItemsPlugin plugin;
    private final Map<String, ItemEntry> byId = new LinkedHashMap<>();

    public ItemsService(ItemsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        byId.clear();
        File file = new File(plugin.getDataFolder(), "items.yml");
        if (!file.exists()) {
            plugin.saveResource("items.yml", false);
        }

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try (var in = plugin.getResource("items.yml")) {
            if (in != null) {
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String key : defaults.getKeys(false)) {
                    if (!yaml.isConfigurationSection(key) && defaults.isConfigurationSection(key)) {
                        yaml.set(key, defaults.getConfigurationSection(key));
                    }
                }
                yaml.save(file);
                yaml = YamlConfiguration.loadConfiguration(file);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not merge items.yml defaults: " + e.getMessage());
        }

        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            if (!section.contains("item") && !section.contains("Item")) {
                plugin.getLogger().warning("items.yml: skip '" + key + "' (no item:)");
                continue;
            }

            Material material = parseMaterial(firstString(section, "item", "Item", "material", "Material"));
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("items.yml: id " + key + " has invalid item");
                continue;
            }

            String displayName = firstString(section, "display_name", "display-name", "name");
            if (displayName == null || displayName.isBlank()) {
                displayName = "&f" + key;
            }

            List<String> lore = firstStringList(section, "lore", "Lore");
            if (lore == null) {
                lore = List.of();
            }

            List<String> effects = firstStringList(section, "effects", "effect", "Effects");
            if (effects == null) {
                effects = List.of();
            }
            boolean chorusOwner = section.getBoolean("chorus-owner", section.getBoolean("chorus_owner", false));

            String id = key.trim().toLowerCase(Locale.ROOT);
            byId.put(id, new ItemEntry(
                    id,
                    material,
                    displayName,
                    List.copyOf(lore),
                    List.copyOf(effects),
                    chorusOwner
            ));
        }
        plugin.getLogger().info("Loaded " + byId.size() + " items");
    }

    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    public ItemEntry byId(String id) {
        if (id == null) {
            return null;
        }
        return byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** Pull / target radius from config.yml {@code traps.<id>.use-radius}. */
    public double useRadius(String id, double def) {
        ConfigurationSection section = trapSection(id);
        if (section == null) {
            return def;
        }
        if (section.contains("use-radius")) {
            return parseDouble(section.get("use-radius"), null, def);
        }
        if (section.contains("use_radius")) {
            return parseDouble(section.get("use_radius"), null, def);
        }
        return def;
    }

    /** Trap lifetime from config.yml {@code traps.<id>.trap-duration}. */
    public int trapDurationSeconds(String id, int def) {
        ConfigurationSection section = trapSection(id);
        if (section == null) {
            return def;
        }
        Object raw = section.get("trap-duration");
        if (raw == null) {
            raw = section.get("trap_duration");
        }
        return parseSeconds(raw, def);
    }

    public boolean allowUseInPs() {
        return plugin.getConfig().getBoolean("traps.allow_use_in_ps", false);
    }

    public boolean allowUseInTrap() {
        return plugin.getConfig().getBoolean("traps.allow_use_in_trap", false);
    }

    private ConfigurationSection trapSection(String id) {
        if (id == null) {
            return null;
        }
        ConfigurationSection traps = plugin.getConfig().getConfigurationSection("traps");
        if (traps == null) {
            return null;
        }
        return traps.getConfigurationSection(id.toLowerCase(Locale.ROOT));
    }

    public boolean chorusOwner(String id) {
        ItemEntry entry = byId(id);
        return entry != null && entry.chorusOwner();
    }

    public List<EffectParser.ParsedEffect> effects(String id) {
        ItemEntry entry = byId(id);
        if (entry == null || entry.effectsRaw().isEmpty()) {
            return List.of();
        }
        return EffectParser.parseAll(entry.effectsRaw(), plugin.getLogger());
    }

    public Component displayName(ItemEntry entry, Map<String, String> placeholders) {
        return deserialize(entry.displayNameRaw(), placeholders);
    }

    public List<Component> lore(ItemEntry entry, Map<String, String> placeholders) {
        List<String> raw = entry.loreRaw();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (line == null) {
                lines.add(Component.empty().decoration(TextDecoration.ITALIC, false));
                continue;
            }
            lines.add(deserialize(line, placeholders));
        }
        return lines;
    }

    private Component deserialize(String raw, Map<String, String> placeholders) {
        TagResolver.Builder builder = TagResolver.builder();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String value = entry.getValue() == null ? "" : TextFormats.toMiniMessage(entry.getValue());
                builder.resolver(Placeholder.parsed(entry.getKey(), value));
            }
        }
        return TextFormats.parse(raw, builder.build());
    }

    private static Material parseMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Material.matchMaterial(name);
        }
    }

    private static double parseDouble(Object primary, Object secondary, double def) {
        Object raw = primary != null ? primary : secondary;
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static int parseSeconds(Object raw, int def) {
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (text.endsWith("s")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        try {
            return Math.max(0, (int) Math.round(Double.parseDouble(text)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String firstString(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (section.isString(key)) {
                return section.getString(key);
            }
        }
        return null;
    }

    private static List<String> firstStringList(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (section.isList(key)) {
                return section.getStringList(key);
            }
        }
        return Collections.emptyList();
    }
}
