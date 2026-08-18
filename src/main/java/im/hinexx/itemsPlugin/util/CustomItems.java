package im.hinexx.itemsPlugin.util;

import im.hinexx.itemsPlugin.ItemsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CustomItems {

    public static final String ID_EXPLODE_TRAP = "explode_trap";
    public static final String ID_UPGR_TRAP = "upgr_trap";
    public static final String ID_DEFAULT = "default";
    public static final String ID_DIZORENT = "dizorent";

    @Deprecated
    public static final String ID_EXPLODETRAP = ID_EXPLODE_TRAP;

    private static NamespacedKey itemIdKey;
    private static NamespacedKey legacyItemIdKey;

    private CustomItems() {
    }

    public static void init(ItemsPlugin plugin) {
        itemIdKey = new NamespacedKey("vtraps", "item_id");
        legacyItemIdKey = new NamespacedKey("itemsplugin", "item_id");
    }

    public static NamespacedKey itemIdKey() {
        return itemIdKey;
    }

    public static ItemStack create(Material material, String id, Component name, List<Component> lore, int customModelData) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore.stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
        }
        if (customModelData > 0) {
            meta.setCustomModelData(customModelData);
        }
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id.toLowerCase(Locale.ROOT));
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack createFromId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.toLowerCase(Locale.ROOT);
        ItemsService items = ItemsPlugin.getInstance().items();
        ItemsService.ItemEntry entry = items.byId(key);
        if (entry == null) {
            ItemsPlugin.getInstance().getLogger().warning("No items.yml entry for id: " + key);
            return null;
        }
        return create(
                entry.material(),
                entry.id(),
                items.displayName(entry, Map.of()),
                items.lore(entry, Map.of()),
                0
        );
    }

    public static ItemStack createFromVisualisation(Material material, String functionId, int customModelData) {
        return createFromVisualisation(material, functionId, Map.of(), customModelData);
    }

    public static ItemStack createFromVisualisation(Material material, String functionId, Map<String, String> placeholders, int customModelData) {
        ItemsService items = ItemsPlugin.getInstance().items();
        ItemsService.ItemEntry entry = items.byId(functionId);
        if (entry == null) {
            ItemsPlugin.getInstance().getLogger().warning("No items.yml entry for function: " + functionId);
            return create(
                    material,
                    functionId,
                    Component.text(functionId),
                    List.of(),
                    customModelData
            );
        }
        return create(
                entry.material() != null ? entry.material() : material,
                entry.id(),
                items.displayName(entry, placeholders),
                items.lore(entry, placeholders),
                customModelData
        );
    }

    public static String readId(ItemStack item) {
        if (item == null || item.getType().isAir() || itemIdKey == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String value = meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (value == null) {
            value = meta.getPersistentDataContainer().get(legacyItemIdKey, PersistentDataType.STRING);
        }
        return value;
    }

    public static boolean is(ItemStack item, String id) {
        return is(item, id, null);
    }

    public static boolean is(ItemStack item, String id, Material expectedType) {
        if (item == null || item.getType().isAir() || itemIdKey == null || id == null) {
            return false;
        }
        if (expectedType != null && item.getType() != expectedType) {
            return false;
        }
        String value = readId(item);
        if (value == null) {
            return false;
        }
        return id.equalsIgnoreCase(value);
    }
}
