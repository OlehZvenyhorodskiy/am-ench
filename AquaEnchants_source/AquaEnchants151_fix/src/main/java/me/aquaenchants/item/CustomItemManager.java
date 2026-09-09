package me.aquaenchants.item;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

public class CustomItemManager {

    private final AquaEnchatsPlugin plugin;
    private final File itemsFolder;
    private final EnchantManager enchantManager;
    private final NamespacedKey customItemIdKey;

    private final Map<String, ItemStack> items = new HashMap<>();

    public CustomItemManager(AquaEnchatsPlugin plugin, File itemsFolder, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.itemsFolder = itemsFolder;
        this.enchantManager = enchantManager;
        this.customItemIdKey = new NamespacedKey(plugin, "custom_item_id");
        reload();
    }

    public void reload() {
        items.clear();

        if (!itemsFolder.exists()) {
            itemsFolder.mkdirs();
        }

        File[] files = itemsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            loadItemFile(file);
        }
    }


    private void loadItemFile(File file) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        // Режим 1: несколько заранее сериализованных ItemStack'ов в одном файле.
        // Формат:
        //   some_id:
        //     ==: org.bukkit.inventory.ItemStack
        //     ...
        if (!cfg.contains("id") && !cfg.contains("material")) {
            for (String key : cfg.getKeys(false)) {
                Object value = cfg.get(key);
                if (value instanceof ItemStack) {
                    String id = key.toLowerCase(Locale.ROOT);
                    ItemStack item = ((ItemStack) value).clone();

                    // Перекрашиваем название и лор предмета, если используются '&'-коды.
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        String display = meta.getDisplayName();
                        if (display != null && !display.isEmpty()) {
                            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', display));
                        }
                        java.util.List<String> lore = meta.getLore();
                        if (lore != null && !lore.isEmpty()) {
                            java.util.List<String> colored = new java.util.ArrayList<>();
                            for (String line : lore) {
                                colored.add(ChatColor.translateAlternateColorCodes('&', line));
                            }
                            meta.setLore(colored);
                        }
                        // Проставляем технический id предмета в PDC,
                        // чтобы по нему можно было находить предметы в слушателях.
                        try {
                            PersistentDataContainer pdc = meta.getPersistentDataContainer();
                            pdc.set(customItemIdKey, PersistentDataType.STRING, id);
                        } catch (Throwable ignored) {}
                        item.setItemMeta(meta);
                    }

                    items.put(id, item);
                    Bukkit.getLogger().info("[AquaEnchats] Loaded item " + id + " from " + file.getName());
                } else {
                    plugin.getLogger().warning("Section '" + key + "' in " + file.getName() + " is not an ItemStack");
                }
            }
            return;
        }

        // Режим 2: старый формат, один предмет на файл
        String id = cfg.getString("id");
        if (id == null) {
            id = file.getName().replace(".yml", "");
        }
        id = id.toLowerCase(Locale.ROOT);

        String materialName = cfg.getString("material", "STONE");
        Material mat;
        try {
            mat = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown material '" + materialName + "' in " + file.getName());
            return;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String name = cfg.getString("name", id);
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        java.util.List<String> lore = cfg.getStringList("lore");
        if (lore != null && !lore.isEmpty()) {
            java.util.List<String> colored = new java.util.ArrayList<>();
            for (String s : lore) {
                colored.add(ChatColor.translateAlternateColorCodes('&', s));
            }
            meta.setLore(colored);
        }

        // Сохраняем технический id предмета в PDC
        try {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(customItemIdKey, PersistentDataType.STRING, id);
        } catch (Throwable ignored) {}

        item.setItemMeta(meta);

        // Зачарования из enachants.yml
        if (cfg.isConfigurationSection("enchants")) {
            Map<CustomEnchant, Integer> map = new HashMap<>();
            for (String enchId : cfg.getConfigurationSection("enchants").getKeys(false)) {
                int level = cfg.getInt("enchants." + enchId, 1);
                CustomEnchant ench = enchantManager.getEnchant(enchId);
                if (ench == null) {
                    plugin.getLogger().warning("Unknown enchant '" + enchId + "' in item " + id);
                    continue;
                }
                map.put(ench, level);
            }
            enchantManager.setEnchantmentsOnItem(item, map);
        }

        items.put(id, item);
        Bukkit.getLogger().info("[AquaEnchats] Loaded item " + id + " from " + file.getName());
    }

    public ItemStack getItem(String id) {
        if (id == null) return null;
        ItemStack item = items.get(id.toLowerCase(Locale.ROOT));
        if (item == null) return null;
        return item.clone();
    }

    public java.util.Set<String> getItemIds() {
        return new java.util.HashSet<>(items.keySet());
    }

}
