package me.aquaenchants.excavator;

import me.aquaenchants.AquaEnchatsPlugin;
import org.bukkit.Location;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Управляет всеми экскаваторами на сервере:
 * загрузка/сохранение в файл и быстрый доступ по Location.
 */
public class ExcavatorManager {

    private final AquaEnchatsPlugin plugin;
    private final File storageFile;
    private final Map<Location, ExcavatorData> excavators = new HashMap<>();

    public ExcavatorManager(AquaEnchatsPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "excavators.yml");
        loadAll();
    }

    private Location normalize(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return loc;
        }
        return new Location(
                loc.getWorld(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ()
        );
    }

    public void addExcavator(Location loc, int initialFuel) {
        if (loc == null) return;
        Location key = normalize(loc);
        ExcavatorData data = new ExcavatorData(key, initialFuel);
        excavators.put(key, data);
        saveAll();
    }

    public ExcavatorData getExcavator(Location loc) {
        if (loc == null) return null;
        return excavators.get(normalize(loc));
    }

    public void updateExcavator(ExcavatorData data) {
        if (data == null || data.getLocation() == null) return;

        // Удаляем старую запись для этого же объекта, если она есть
        Location keyToRemove = null;
        for (Map.Entry<Location, ExcavatorData> entry : excavators.entrySet()) {
            if (entry.getValue() == data) {
                keyToRemove = entry.getKey();
                break;
            }
        }
        if (keyToRemove != null) {
            excavators.remove(keyToRemove);
        }

        // Записываем по новой нормализованной локации
        Location key = normalize(data.getLocation());
        excavators.put(key, data);
        saveAll();
    }


    public void removeExcavator(Location loc) {
        if (loc == null) return;

        Location norm = normalize(loc);
        excavators.remove(norm);

        // На всякий случай чистим все записи, чья локация совпадает по миру и блок-координатам
        Location toRemove = null;
        for (Map.Entry<Location, ExcavatorData> entry : excavators.entrySet()) {
            ExcavatorData data = entry.getValue();
            if (data == null || data.getLocation() == null || loc.getWorld() == null) continue;

            Location dLoc = data.getLocation();
            if (dLoc.getWorld() != null
                    && dLoc.getWorld().getName().equals(loc.getWorld().getName())
                    && dLoc.getBlockX() == loc.getBlockX()
                    && dLoc.getBlockY() == loc.getBlockY()
                    && dLoc.getBlockZ() == loc.getBlockZ()) {
                toRemove = entry.getKey();
                break;
            }
        }
        if (toRemove != null) {
            excavators.remove(toRemove);
        }

        saveAll();
    }


    public Collection<ExcavatorData> getAllExcavators() {
        return new ArrayList<>(excavators.values());
    }

    public void saveAll() {
        YamlConfiguration config = new YamlConfiguration();
        List<ExcavatorData> list = new ArrayList<>(excavators.values());
        config.set("excavators", list);
        try {
            if (!storageFile.getParentFile().exists()) {
                storageFile.getParentFile().mkdirs();
            }
            config.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save excavators.yml: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void loadAll() {
        excavators.clear();
        if (!storageFile.exists()) {
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(storageFile);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("Failed to load excavators.yml: " + e.getMessage());
            return;
        }

        List<?> list = config.getList("excavators");
        if (list == null) {
            return;
        }

        for (Object obj : list) {
            if (obj instanceof ExcavatorData) {
                ExcavatorData data = (ExcavatorData) obj;
                if (data.getLocation() != null) {
                    excavators.put(normalize(data.getLocation()), data);
                }
            } else if (obj instanceof Map) {
                // На всякий случай поддерживаем "сырую" Map-сериализацию
                ExcavatorData data = ExcavatorData.deserialize((Map<String, Object>) obj);
                if (data != null && data.getLocation() != null) {
                    excavators.put(normalize(data.getLocation()), data);
                }
            }
        }

        plugin.getLogger().info("Loaded " + excavators.size() + " excavators from file.");
    }
}
