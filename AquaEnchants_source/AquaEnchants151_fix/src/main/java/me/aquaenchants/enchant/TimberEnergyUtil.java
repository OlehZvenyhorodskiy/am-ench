package me.aquaenchants.enchant;

import me.aquaenchants.AquaEnchatsPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Утилита для хранения и расхода энергии зачарования Timber на предмете.
 * Энергия хранится в PersistentDataContainer предмета и не теряется при перезаходе.
 */
public final class TimberEnergyUtil {

    /** Количество заряда, которое тратится за одно срабатывание Timber. */
    public static final int ENERGY_PER_USE = 2;

    private static final String KEY_ENERGY = "timber_energy";

    private TimberEnergyUtil() {
    }

    private static NamespacedKey getEnergyKey() {
        AquaEnchatsPlugin plugin = AquaEnchatsPlugin.getInstance();
        if (plugin == null) {
            // На всякий случай возвращаем временный ключ, но без плагина ничего не запишется.
            return new NamespacedKey("aquaenchants", KEY_ENERGY);
        }
        return new NamespacedKey(plugin, KEY_ENERGY);
    }

    public static int getEnergy(ItemStack item) {
        if (item == null) return 0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        Integer value = container.get(getEnergyKey(), PersistentDataType.INTEGER);
        return value != null ? Math.max(0, value) : 0;
    }

    public static void setEnergy(ItemStack item, int energy) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (energy < 0) energy = 0;

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(getEnergyKey(), PersistentDataType.INTEGER, energy);
        item.setItemMeta(meta);
    }

    public static boolean hasEnergy(ItemStack item, int required) {
        return getEnergy(item) >= required;
    }

    /**
     * Пытается списать указанное количество энергии с предмета.
     *
     * @return true, если энергии хватило и она была списана; false, если энергии не хватило.
     */
    public static boolean consumeEnergy(ItemStack item, int amount) {
        int current = getEnergy(item);
        if (current < amount) {
            return false;
        }
        setEnergy(item, current - amount);
        return true;
    }

    /**
     * Добавляет энергию с учетом максимального значения.
     *
     * @param maxEnergy максимальное значение энергии (0 или меньше — без ограничения)
     * @return новое значение энергии
     */
    public static int addEnergy(ItemStack item, int amount, int maxEnergy) {
        if (item == null) return 0;
        int current = getEnergy(item);
        int result = current + amount;
        if (maxEnergy > 0 && result > maxEnergy) {
            result = maxEnergy;
        }
        setEnergy(item, result);
        return result;
    }
}
