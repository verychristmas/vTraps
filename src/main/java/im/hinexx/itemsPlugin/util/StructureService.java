package im.hinexx.itemsPlugin.util;

import im.hinexx.itemsPlugin.ItemsPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public final class StructureService {

    public static final String FOLDER_NAME = "structures";

    private final ItemsPlugin plugin;

    public StructureService(ItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public File folder() {
        return new File(plugin.getDataFolder(), FOLDER_NAME);
    }

    public void ensureAndExport() {
        File dir = folder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create structures folder");
            return;
        }

        // 1) старая папка plugins/vTraps/schematics → structures
        copyMissingSchems(new File(plugin.getDataFolder(), "schematics"), dir);
        // 2) WorldEdit schematics (часто туда сохраняют //schem save)
        PluginPath worldEdit = findWorldEditSchematics();
        if (worldEdit != null) {
            copyMissingSchems(worldEdit.dir, dir);
        }
        // 3) дефолты из jar (только если файла ещё нет — твои схемы не перезапишет)
        exportBundledStructures(dir);

        logAvailable(dir);
        warnMissingConfigured(dir);
    }

    public boolean isNone(String structureName) {
        if (structureName == null) {
            return true;
        }
        String trimmed = structureName.trim();
        return trimmed.isEmpty() || trimmed.equalsIgnoreCase("none");
    }

    public String configuredStructure(String configKey) {
        return plugin.getConfig().getString("structures." + configKey + ".structure", "none");
    }

    /** @deprecated closed-schem больше не используется — крыша заливается блоками окружения */
    @Deprecated
    public String configuredClosedStructure(String configKey) {
        return plugin.getConfig().getString("structures." + configKey + ".structure-closed", "none");
    }

    public File resolve(String structureName) {
        if (isNone(structureName)) {
            return null;
        }
        String name = structureName.trim();
        File inStructures = new File(folder(), name);
        if (inStructures.isFile()) {
            return inStructures;
        }
        File inLegacy = new File(plugin.getDataFolder(), "schematics/" + name);
        if (inLegacy.isFile()) {
            return inLegacy;
        }
        PluginPath we = findWorldEditSchematics();
        if (we != null) {
            File inWe = new File(we.dir, name);
            if (inWe.isFile()) {
                return inWe;
            }
        }
        return inStructures;
    }

    public File resolveConfigured(String configKey) {
        return resolve(configuredStructure(configKey));
    }

    /** @deprecated use roof filler instead */
    @Deprecated
    public File resolveConfiguredClosed(String configKey) {
        return resolve(configuredClosedStructure(configKey));
    }

    private void logAvailable(File dir) {
        File[] files = dir.listFiles((d, n) -> {
            String l = n.toLowerCase(Locale.ROOT);
            return l.endsWith(".schem") || l.endsWith(".schematic");
        });
        if (files == null || files.length == 0) {
            plugin.getLogger().info("structures/: пусто — положи .schem и пропиши имя в config.yml");
            return;
        }
        String list = Arrays.stream(files).map(File::getName).sorted().collect(Collectors.joining(", "));
        plugin.getLogger().info("structures/: " + list);
    }

    private void warnMissingConfigured(File dir) {
        var section = plugin.getConfig().getConfigurationSection("structures");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String name = configuredStructure(key);
            if (isNone(name)) {
                continue;
            }
            File f = resolve(name);
            if (f == null || !f.isFile()) {
                plugin.getLogger().warning("structures." + key + ".structure = " + name
                        + " — файл не найден в " + dir.getPath());
            }
        }
    }

    private void copyMissingSchems(File from, File to) {
        if (from == null || !from.isDirectory()) {
            return;
        }
        File[] files = from.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".schem") || lower.endsWith(".schematic");
        });
        if (files == null) {
            return;
        }
        for (File file : files) {
            File target = new File(to, file.getName());
            // копируем если нет ИЛИ исходник новее (пересохранили //schem save)
            if (target.isFile() && target.lastModified() >= file.lastModified() && target.length() == file.length()) {
                continue;
            }
            try {
                Files.copy(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Скопирован schem → structures/" + file.getName()
                        + " (" + file.length() + " bytes)");
            } catch (IOException e) {
                plugin.getLogger().warning("Не скопировать " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private PluginPath findWorldEditSchematics() {
        var we = plugin.getServer().getPluginManager().getPlugin("WorldEdit");
        if (we == null) {
            return null;
        }
        File dir = new File(we.getDataFolder(), "schematics");
        if (!dir.isDirectory()) {
            return null;
        }
        return new PluginPath(dir);
    }

    private void exportBundledStructures(File structuresDir) {
        URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
        if (location == null) {
            return;
        }
        try {
            File codeSource = new File(location.toURI());
            if (codeSource.isFile() && codeSource.getName().endsWith(".jar")) {
                try (JarFile jar = new JarFile(codeSource)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith("structures/")) {
                            continue;
                        }
                        String fileName = name.substring("structures/".length());
                        if (fileName.isEmpty() || fileName.contains("/") || fileName.equalsIgnoreCase("README.txt")) {
                            continue;
                        }
                        File out = new File(structuresDir, fileName);
                        if (out.exists()) {
                            continue;
                        }
                        try (InputStream in = jar.getInputStream(entry)) {
                            Files.copy(in, out.toPath());
                            plugin.getLogger().info("Скопирован schem из jar → structures/" + fileName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not export bundled structures: " + e.getMessage());
        }
    }

    private record PluginPath(File dir) {
    }
}
