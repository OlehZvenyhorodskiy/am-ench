package me.aquaenchants.config;

import me.aquaenchants.AquaEnchatsPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;

/**
 * Manages global enchanting table drop chances and bookshelf bonus calculations.
 */
public class TableSettingsManager {

    private final AquaEnchatsPlugin plugin;

    private int minBonusChance = 5;
    private int maxBonusChance = 20;

    private int slot1CustomChance = 10;
    private int slot2CustomChance = 20;
    private int slot3CustomChance = 30;

    public TableSettingsManager(AquaEnchatsPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        FileConfiguration config = plugin.getConfig();
        if (config.isConfigurationSection("table_settings")) {
            this.minBonusChance = config.getInt("table_settings.min_bonus_chance", 5);
            this.maxBonusChance = config.getInt("table_settings.max_bonus_chance", 20);
            this.slot1CustomChance = config.getInt("table_settings.slot1_custom_chance", 10);
            this.slot2CustomChance = config.getInt("table_settings.slot2_custom_chance", 20);
            this.slot3CustomChance = config.getInt("table_settings.slot3_custom_chance", 30);
        } else {
            save();
        }
    }

    public void save() {
        try {
            FileConfiguration config = plugin.getConfig();
            config.set("table_settings.min_bonus_chance", minBonusChance);
            config.set("table_settings.max_bonus_chance", maxBonusChance);
            config.set("table_settings.slot1_custom_chance", slot1CustomChance);
            config.set("table_settings.slot2_custom_chance", slot2CustomChance);
            config.set("table_settings.slot3_custom_chance", slot3CustomChance);
            plugin.saveConfig();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save table_settings: " + e.getMessage());
        }
    }

    /**
     * Calculates the extra bonus enchant chance based on bookshelf count.
     */
    public int calculateBookshelfBonusChance(int bookshelves) {
        int power = Math.max(0, Math.min(15, bookshelves));
        return minBonusChance + (int) Math.round((maxBonusChance - minBonusChance) * (power / 15.0));
    }

    /**
     * Calculates the custom enchantment roll chance for a given slot index (0, 1, 2) and bookshelf power.
     */
    public double calculateSlotCustomChance(int slotIndex, int bookshelves) {
        int power = Math.max(0, Math.min(15, bookshelves));
        return switch (slotIndex) {
            case 0 -> slot1CustomChance + (power * 0.3);
            case 1 -> slot2CustomChance + (power * 0.5);
            case 2 -> slot3CustomChance + (power * 0.8);
            default -> 20.0;
        };
    }

    public int getMinBonusChance() {
        return minBonusChance;
    }

    public void setMinBonusChance(int minBonusChance) {
        this.minBonusChance = Math.max(0, Math.min(100, minBonusChance));
        if (this.minBonusChance > this.maxBonusChance) {
            this.maxBonusChance = this.minBonusChance;
        }
    }

    public int getMaxBonusChance() {
        return maxBonusChance;
    }

    public void setMaxBonusChance(int maxBonusChance) {
        this.maxBonusChance = Math.max(0, Math.min(100, maxBonusChance));
        if (this.maxBonusChance < this.minBonusChance) {
            this.minBonusChance = this.maxBonusChance;
        }
    }

    public int getSlot1CustomChance() {
        return slot1CustomChance;
    }

    public void setSlot1CustomChance(int slot1CustomChance) {
        this.slot1CustomChance = Math.max(0, Math.min(100, slot1CustomChance));
    }

    public int getSlot2CustomChance() {
        return slot2CustomChance;
    }

    public void setSlot2CustomChance(int slot2CustomChance) {
        this.slot2CustomChance = Math.max(0, Math.min(100, slot2CustomChance));
    }

    public int getSlot3CustomChance() {
        return slot3CustomChance;
    }

    public void setSlot3CustomChance(int slot3CustomChance) {
        this.slot3CustomChance = Math.max(0, Math.min(100, slot3CustomChance));
    }
}
