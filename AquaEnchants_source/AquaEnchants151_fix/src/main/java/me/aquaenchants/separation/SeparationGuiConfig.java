package me.aquaenchants.separation;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Настройки GUI для Separation (вынесены в отдельный yml).
 */
public final class SeparationGuiConfig {

    public static final String FILE_NAME = "separation_gui.yml";

    private final JavaPlugin plugin;
    private final File file;
    private FileConfiguration cfg;

    public SeparationGuiConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        ensureExists();
        reload();
    }

    private void ensureExists() {
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
    }

    public void reload() {
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public String getSelectTitle() {
        return color(cfg.getString("select.title", "&6Выбор зачарования"));
    }

    public int getSelectSize() {
        int size = cfg.getInt("select.size", 54);
        // Bukkit поддерживает размеры кратные 9
        if (size < 9) size = 9;
        if (size % 9 != 0) size = (size / 9) * 9;
        return Math.min(size, 54);
    }

    public int getItemSlot() {
        return cfg.getInt("select.item_slot", 4);
    }

    public int getOptionStartSlot() {
        return cfg.getInt("select.option_start", 18);
    }

    public int getCostSlot() {
        return cfg.getInt("select.cost.slot", 53);
    }

    public Set<Integer> getFillerSlots() {
        List<String> raw = cfg.getStringList("select.filler.slots");
        Set<Integer> out = new HashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;
            if (s.contains("-")) {
                String[] parts = s.split("-", 2);
                try {
                    int a = Integer.parseInt(parts[0].trim());
                    int b = Integer.parseInt(parts[1].trim());
                    int from = Math.min(a, b);
                    int to = Math.max(a, b);
                    for (int i = from; i <= to; i++) out.add(i);
                } catch (NumberFormatException ignored) {
                }
            } else {
                try {
                    out.add(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    public ItemStack createFillerItem() {
        String matName = cfg.getString("select.filler.material", "GRAY_STAINED_GLASS_PANE");
        Material mat = Material.matchMaterial(matName == null ? "" : matName);
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(cfg.getString("select.filler.name", "&8 ")));
            it.setItemMeta(meta);
        }
        return it;
    }

    public ItemStack createCostItem(Material currencyMaterial, int currencyAmount, String currencyPrettyName) {
        Material mat = currencyMaterial == null ? Material.ECHO_SHARD : currencyMaterial;
        int amt = Math.max(1, currencyAmount);
        ItemStack it = new ItemStack(mat, amt);

        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String nameTpl = cfg.getString("select.cost.name", "&6Стоимость: &e%amount% &fx %item%");
            String name = (nameTpl == null ? "" : nameTpl)
                    .replace("%amount%", String.valueOf(amt))
                    .replace("%item%", currencyPrettyName == null ? "" : currencyPrettyName);
            meta.setDisplayName(color(name));

            List<String> loreTpl = cfg.getStringList("select.cost.lore");
            if (loreTpl != null && !loreTpl.isEmpty()) {
                List<String> lore = new ArrayList<>(loreTpl.size());
                for (String l : loreTpl) {
                    if (l == null) continue;
                    lore.add(color(l)
                            .replace("%amount%", String.valueOf(amt))
                            .replace("%item%", currencyPrettyName == null ? "" : currencyPrettyName));
                }
                meta.setLore(lore);
            }

            it.setItemMeta(meta);
        }

        return it;
    }

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
