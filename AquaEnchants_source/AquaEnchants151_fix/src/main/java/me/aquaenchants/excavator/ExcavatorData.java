package me.aquaenchants.excavator;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Данные одного экскаватора.
 * Хранятся в памяти и сериализуются в файл excavators.yml.
 */
@SerializableAs("ExcavatorData")
public class ExcavatorData implements ConfigurationSerializable {

    public enum ExcavatorMode {
        BELOW,  // копаем только ниже экскаватора
        CHUNK   // копаем весь чанк
    }

    private Location location;
    private int fuel;
    private boolean working;
    private ExcavatorMode mode;
    
    // Координаты копания по полосам
    private int currentY;      // Текущий слой (высота)
    private int currentZ;      // Текущая полоса в слое (0-15)
    
    // Собранные руды (хранятся как строки Material для сериализации)
    private final List<String> collectedItems = new ArrayList<>();

    public ExcavatorData(Location location, int fuel) {
        this(location, fuel, ExcavatorMode.BELOW, false,
                location != null ? location.getBlockY() : 0,
                0,  // currentZ по умолчанию 0
                new ArrayList<>());
    }

    public ExcavatorData(Location location,
                         int fuel,
                         ExcavatorMode mode,
                         boolean working,
                         int currentY,
                         int currentZ,
                         List<String> collectedItems) {
        this.location = location;
        this.fuel = fuel;
        this.mode = mode == null ? ExcavatorMode.BELOW : mode;
        this.working = working;
        this.currentY = currentY;
        this.currentZ = currentZ;
        if (collectedItems != null) {
            this.collectedItems.addAll(collectedItems);
        }
    }

    // ===== API для работы с топливом =====

    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    public int getFuel() {
        return fuel;
    }

    public void setFuel(int fuel) {
        this.fuel = Math.max(0, fuel);
    }

    public void addFuel(int amount) {
        if (amount <= 0) return;
        this.fuel += amount;
    }

    public void consumeFuel(int amount) {
        if (amount <= 0) return;
        this.fuel = Math.max(0, this.fuel - amount);
    }

    // ===== API для состояния работы =====

    public boolean isWorking() {
        return working;
    }

    public void setWorking(boolean working) {
        this.working = working;
    }

    public ExcavatorMode getMode() {
        return mode;
    }

    public void setMode(ExcavatorMode mode) {
        if (mode != null) {
            this.mode = mode;
        }
    }

    // ===== API для координат копания =====

    public int getCurrentY() {
        return currentY;
    }

    public void setCurrentY(int currentY) {
        this.currentY = currentY;
    }

    public int getCurrentZ() {
        return currentZ;
    }

    public void setCurrentZ(int currentZ) {
        this.currentZ = currentZ;
    }

    // Устаревший метод для совместимости (теперь используется currentY)
    @Deprecated
    public int getCurrentLayer() {
        return currentY;
    }

    @Deprecated
    public void setCurrentLayer(int currentLayer) {
        this.currentY = currentLayer;
    }

    // ===== API для работы с собранными предметами =====

    /**
     * Получить список собранных предметов как строки Material
     */
    public List<String> getCollectedItems() {
        return new ArrayList<>(collectedItems);
    }

    /**
     * Добавить собранный предмет по имени материала
     */
    public void addCollectedItem(String materialName) {
        if (materialName == null || materialName.isEmpty()) return;
        this.collectedItems.add(materialName);
    }

    /**
     * Получить собранные предметы как ItemStack для помещения в шалкер
     */
    public List<ItemStack> getCollectedOres() {
        List<ItemStack> ores = new ArrayList<>();
        for (String itemName : collectedItems) {
            try {
                Material material = Material.valueOf(itemName);
                ores.add(new ItemStack(material, 1));
            } catch (IllegalArgumentException e) {
                // Игнорируем неизвестные материалы
            }
        }
        return ores;
    }

    /**
     * Установить весь список собранных предметов (для процессора)
     */
    public void setCollectedOres(List<ItemStack> ores) {
        this.collectedItems.clear();
        if (ores != null) {
            for (ItemStack item : ores) {
                if (item != null && item.getType() != Material.AIR) {
                    this.collectedItems.add(item.getType().name());
                }
            }
        }
    }

    /**
     * Добавить собранный ItemStack
     */
    public void addCollectedOre(ItemStack item) {
        if (item != null && item.getType() != Material.AIR) {
            this.collectedItems.add(item.getType().name());
        }
    }

    /**
     * Очистить список собранных предметов
     */
    public void clearCollectedItems() {
        this.collectedItems.clear();
    }

    // ===== Сериализация в YAML =====

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        if (location != null && location.getWorld() != null) {
            map.put("world", location.getWorld().getName());
            map.put("x", location.getBlockX());
            map.put("y", location.getBlockY());
            map.put("z", location.getBlockZ());
        }
        map.put("fuel", fuel);
        map.put("working", working);
        map.put("mode", mode.name());
        map.put("currentY", currentY);
        map.put("currentZ", currentZ);
        map.put("items", new ArrayList<>(collectedItems));
        return map;
    }

    @SuppressWarnings("unchecked")
    public static ExcavatorData deserialize(Map<String, Object> map) {
        if (map == null) {
            return null;
        }

        String worldName = (String) map.get("world");
        Integer x = castInt(map.get("x"));
        Integer y = castInt(map.get("y"));
        Integer z = castInt(map.get("z"));

        Location loc = null;
        if (worldName != null && x != null && y != null && z != null) {
            loc = new Location(Bukkit.getWorld(worldName), x, y, z);
        }

        int fuel = castInt(map.get("fuel"), 0);
        boolean working = map.get("working") instanceof Boolean ? (Boolean) map.get("working") : false;

        ExcavatorMode mode = ExcavatorMode.BELOW;
        Object modeObj = map.get("mode");
        if (modeObj instanceof String) {
            try {
                mode = ExcavatorMode.valueOf(((String) modeObj).toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        // Поддержка старого формата (currentLayer) и нового (currentY)
        int currentY = castInt(map.get("currentY"), -1);
        if (currentY == -1) {
            currentY = castInt(map.get("currentLayer"), loc != null ? loc.getBlockY() : 0);
        }

        int currentZ = castInt(map.get("currentZ"), 0);

        List<String> items = new ArrayList<>();
        Object itemsObj = map.get("items");
        if (itemsObj instanceof List<?>) {
            for (Object o : (List<?>) itemsObj) {
                if (o != null) {
                    items.add(o.toString());
                }
            }
        }

        return new ExcavatorData(loc, fuel, mode, working, currentY, currentZ, items);
    }

    private static Integer castInt(Object value) {
        return castInt(value, null);
    }

    private static Integer castInt(Object value, Integer def) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
