package me.aquaenchants.hook;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Reads vanilla enchant maximum levels from CMI config (plugins/CMI/config.yml).
 * Falls back to Bukkit default max levels when CMI config is missing.
 */
public class CmiEnchantLimits {

    private final Map<String, Integer> maxByKey;

    public CmiEnchantLimits(Map<String, Integer> maxByKey) {
        this.maxByKey = (maxByKey == null) ? Collections.emptyMap() : new HashMap<>(maxByKey);
    }

    public static CmiEnchantLimits loadFromCmiConfig(File pluginsDir) {
        try {
            File cmiCfg = new File(new File(pluginsDir, "CMI"), "config.yml");
            if (!cmiCfg.isFile()) {
                return new CmiEnchantLimits(Collections.emptyMap());
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(cmiCfg);
            ConfigurationSection sec = yaml.getConfigurationSection("Enchanting.enchantLimits.MaxLevel");
            if (sec == null) {
                return new CmiEnchantLimits(Collections.emptyMap());
            }

            Map<String, Integer> map = new HashMap<>();
            for (String k : sec.getKeys(false)) {
                int v = sec.getInt(k, -1);
                if (v > 0) {
                    map.put(k.toLowerCase(Locale.ROOT), v);
                }
            }
            Bukkit.getLogger().info("[AquaEnchats] Loaded " + map.size() + " vanilla enchant limits from CMI config.yml");
            return new CmiEnchantLimits(map);
        } catch (Throwable t) {
            Bukkit.getLogger().log(Level.WARNING, "[AquaEnchats] Failed to read CMI enchant limits; using Bukkit defaults", t);
            return new CmiEnchantLimits(Collections.emptyMap());
        }
    }

    /**
     * Returns max level allowed for this vanilla enchant, according to CMI config.
     */
    public int getMaxLevel(Enchantment enchantment) {
        if (enchantment == null) return 0;

        String mcKey = enchantment.getKey().getKey().toLowerCase(Locale.ROOT);
        Integer direct = maxByKey.get(mcKey);
        if (direct != null) return direct;

        String legacy = toCmiLegacyKey(mcKey);
        if (legacy != null) {
            Integer v = maxByKey.get(legacy);
            if (v != null) return v;
        }

        // Also try Bukkit-style name if present in config (rare)
        try {
            String bukkitName = enchantment.getName().toLowerCase(Locale.ROOT);
            Integer v = maxByKey.get(bukkitName);
            if (v != null) return v;
        } catch (Throwable ignored) {
        }

        return enchantment.getMaxLevel();
    }

    private static String toCmiLegacyKey(String mcKey) {
        // CMI uses older Bukkit keys (damage_all, dig_speed, etc.)
        return switch (mcKey) {
            case "sharpness" -> "damage_all";
            case "smite" -> "damage_undead";
            case "bane_of_arthropods" -> "damage_arthropods";
            case "efficiency" -> "dig_speed";
            case "unbreaking" -> "durability";
            case "fire_aspect" -> "fire_aspect";
            case "knockback" -> "knockback";
            case "looting" -> "loot_bonus_mobs";
            case "fortune" -> "loot_bonus_blocks";
            case "power" -> "arrow_damage";
            case "punch" -> "arrow_knockback";
            case "flame" -> "arrow_fire";
            case "infinity" -> "arrow_infinite";
            case "protection" -> "protection_environmental";
            case "blast_protection" -> "protection_explosions";
            case "fire_protection" -> "protection_fire";
            case "projectile_protection" -> "protection_projectile";
            case "feather_falling" -> "protection_fall";
            case "respiration" -> "oxygen";
            case "aqua_affinity" -> "water_worker";
            default -> mcKey; // many match (mending, thorns, frost_walker, etc.)
        };
    }
}
