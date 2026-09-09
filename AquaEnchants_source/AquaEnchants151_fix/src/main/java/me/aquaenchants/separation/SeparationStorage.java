package me.aquaenchants.separation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Хранилище предметов для процесса "снятия зачарований".
 *
 * Формат separation.yml:
 * <uuid>:
 *   item: <base64>
 */
public final class SeparationStorage {

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration yml;

    public SeparationStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "separation.yml");
        this.yml = YamlConfiguration.loadConfiguration(file);
    }

    public synchronized void saveItem(UUID uuid, String base64) {
        if (uuid == null) return;
        if (base64 == null) base64 = "";
        yml.set(uuid.toString() + ".item", base64);
        flush();
    }

    public synchronized String loadItem(UUID uuid) {
        if (uuid == null) return null;
        return yml.getString(uuid.toString() + ".item");
    }

    public synchronized void remove(UUID uuid) {
        if (uuid == null) return;
        yml.set(uuid.toString(), null);
        flush();
    }

    private void flush() {
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить separation.yml: " + e.getMessage());
        }
    }
}
