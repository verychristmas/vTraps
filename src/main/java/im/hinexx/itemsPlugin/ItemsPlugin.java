package im.hinexx.itemsPlugin;

import im.hinexx.itemsPlugin.Commands.CustomItemGiveCommand;
import im.hinexx.itemsPlugin.Items.DefaultTrap;
import im.hinexx.itemsPlugin.Items.Dizorent;
import im.hinexx.itemsPlugin.Items.ExplodeTrap;
import im.hinexx.itemsPlugin.Items.UpgrTrap;
import im.hinexx.itemsPlugin.util.ActiveTrapZones;
import im.hinexx.itemsPlugin.util.CooldownService;
import im.hinexx.itemsPlugin.util.CustomItems;
import im.hinexx.itemsPlugin.util.ItemsService;
import im.hinexx.itemsPlugin.util.Messages;
import im.hinexx.itemsPlugin.util.ProtectionHook;
import im.hinexx.itemsPlugin.util.SchematicCache;
import im.hinexx.itemsPlugin.util.StructureService;
import im.hinexx.itemsPlugin.util.TrapBlockLedger;
import im.hinexx.itemsPlugin.util.TrapRoofFiller;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ItemsPlugin extends JavaPlugin {

    private static ItemsPlugin instance;
    private Messages messages;
    private ItemsService items;
    private CooldownService cooldowns;
    private StructureService structures;
    private SchematicCache schematics;
    private ExplodeTrap explodeTrap;
    private UpgrTrap upgrTrap;
    private DefaultTrap defaultTrap;
    private Dizorent dizorent;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder");
        }

        getConfig().options().copyDefaults(true);
        saveConfig();
        saveResourceIfMissing("items.yml");
        saveResourceIfMissing("messages.yml");

        structures = new StructureService(this);
        structures.ensureAndExport();
        schematics = new SchematicCache(getLogger());
        TrapRoofFiller.setLogger(getLogger());
        TrapBlockLedger.setLogger(getLogger());

        messages = new Messages(this);
        items = new ItemsService(this);
        cooldowns = new CooldownService(this);
        CustomItems.init(this);

        getServer().getScheduler().runTask(this, this::initProtection);

        explodeTrap = new ExplodeTrap(this);
        upgrTrap = new UpgrTrap(this);
        defaultTrap = new DefaultTrap(this);
        dizorent = new Dizorent(this);

        getServer().getPluginManager().registerEvents(explodeTrap, this);
        getServer().getPluginManager().registerEvents(upgrTrap, this);
        getServer().getPluginManager().registerEvents(defaultTrap, this);
        getServer().getPluginManager().registerEvents(dizorent, this);

        CustomItemGiveCommand command = new CustomItemGiveCommand();
        PluginCommand pluginCommand = getCommand("vtraps");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        warmSchematicCache();
        getLogger().info("vTraps enabled. Items: " + items.ids());
    }

    @Override
    public void onDisable() {
        if (explodeTrap != null) {
            explodeTrap.cleanup();
        }
        if (upgrTrap != null) {
            upgrTrap.cleanup();
        }
        if (defaultTrap != null) {
            defaultTrap.cleanup();
        }
        if (dizorent != null) {
            dizorent.cleanup();
        }
        if (schematics != null) {
            schematics.clear();
        }
        TrapBlockLedger.clearAll();
        ActiveTrapZones.clearAll();
        instance = null;
    }

    public void reloadPlugin() {
        reloadConfig();
        saveResourceIfMissing("items.yml");
        saveResourceIfMissing("messages.yml");
        if (messages == null) {
            messages = new Messages(this);
        } else {
            messages.reload();
        }
        if (items == null) {
            items = new ItemsService(this);
        } else {
            items.reload();
        }
        if (cooldowns == null) {
            cooldowns = new CooldownService(this);
        }
        if (structures == null) {
            structures = new StructureService(this);
        }
        structures.ensureAndExport();
        if (schematics == null) {
            schematics = new SchematicCache(getLogger());
        } else {
            schematics.clear();
        }
        if (dizorent != null) {
            dizorent.reloadFromConfig();
        }
        initProtection();
        warmSchematicCache();
        getLogger().info("vTraps reloaded (config, messages, items, structures)");
    }

    private void initProtection() {
        ProtectionHook.init(
                getLogger(),
                getConfig().getBoolean("protection.use-trap-in-region", false),
                getConfig().getBoolean("protection.protect-ps-blocks", true),
                getConfig().getBoolean("traps.allow_use_in_ps", false)
        );
    }

    private void saveResourceIfMissing(String name) {
        java.io.File file = new java.io.File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }

    private void warmSchematicCache() {
        if (schematics == null || structures == null) {
            return;
        }
        for (String key : new String[]{"explode-trap", "upgr-trap", "default-trap"}) {
            var open = structures.resolveConfigured(key);
            if (open != null) {
                schematics.getBaked(open, 0);
            }
        }
    }

    public Messages messages() {
        return messages;
    }

    public ItemsService items() {
        return items;
    }

    public CooldownService cooldowns() {
        return cooldowns;
    }

    public StructureService structures() {
        return structures;
    }

    public SchematicCache schematics() {
        return schematics;
    }

    public static ItemsPlugin getInstance() {
        return instance;
    }
}
