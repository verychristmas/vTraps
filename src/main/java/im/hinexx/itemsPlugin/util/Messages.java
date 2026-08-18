package im.hinexx.itemsPlugin.util;

import im.hinexx.itemsPlugin.ItemsPlugin;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Messages {

    private final ItemsPlugin plugin;
    private FileConfiguration messages;
    private String prefixRaw = "";

    public Messages(ItemsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        try (var in = plugin.getResource("messages.yml")) {
            if (in != null) {
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                messages.setDefaults(defaults);
                messages.options().copyDefaults(true);
                messages.save(file);
                messages = YamlConfiguration.loadConfiguration(file);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not merge messages defaults: " + e.getMessage());
        }
        prefixRaw = messages.getString("prefix", "");
    }

    public String raw(String path) {
        String value = messages.getString(path);
        if (value == null) {
            plugin.getLogger().warning("Missing message: " + path);
            return "<red>Missing message: " + path + "</red>";
        }
        return value;
    }

    public String raw(String path, Map<String, String> placeholders) {
        String value = raw(path);
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                String v = e.getValue() == null ? "" : e.getValue();
                value = value.replace("<" + e.getKey() + ">", v).replace("%" + e.getKey() + "%", v);
            }
        }
        return value;
    }

    public List<String> rawList(String path) {
        List<String> list = messages.getStringList(path);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list;
    }

    public Component get(String path, TagResolver... resolvers) {
        TagResolver.Builder builder = TagResolver.builder()
                .resolver(Placeholder.parsed("prefix", TextFormats.toMiniMessage(prefixRaw)));
        for (TagResolver resolver : resolvers) {
            builder.resolver(resolver);
        }
        return TextFormats.parse(raw(path), builder.build());
    }

    public Component get(String path, Map<String, String> placeholders) {
        return get(path, toResolvers(placeholders));
    }

    public List<Component> getList(String path, Map<String, String> placeholders) {
        TagResolver[] resolvers = toResolvers(placeholders);
        List<Component> result = new ArrayList<>();
        for (String line : rawList(path)) {
            TagResolver.Builder builder = TagResolver.builder()
                    .resolver(Placeholder.parsed("prefix", TextFormats.toMiniMessage(prefixRaw)));
            for (TagResolver resolver : resolvers) {
                builder.resolver(resolver);
            }
            result.add(TextFormats.parse(line, builder.build()));
        }
        return result;
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        sender.sendMessage(get(path, placeholders));
    }

    public void send(CommandSender sender, String path, TagResolver... resolvers) {
        sender.sendMessage(get(path, resolvers));
    }

    public void send(Audience audience, String path, TagResolver... resolvers) {
        audience.sendMessage(get(path, resolvers));
    }

    public static Map<String, String> placeholders(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders require even number of args");
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private TagResolver[] toResolvers(Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return new TagResolver[0];
        }
        TagResolver[] resolvers = new TagResolver[placeholders.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue() == null ? "" : TextFormats.toMiniMessage(entry.getValue());
            resolvers[i++] = Placeholder.parsed(entry.getKey(), value);
        }
        return resolvers;
    }
}
