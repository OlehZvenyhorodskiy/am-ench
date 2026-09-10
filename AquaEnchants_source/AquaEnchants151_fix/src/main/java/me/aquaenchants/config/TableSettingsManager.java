package me.aquaenchants.config;

import me.aquaenchants.AquaEnchatsPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manages global enchanting table drop chances and bookshelf bonus calculations.
 */
public class TableSettingsManager {

    private final AquaEnchatsPlugin plugin;

    private int minBonusChance = 5;
    private int maxBonusChance = 20;

    // Legacy per-slot custom chances (kept for backwards compatibility with old config.yml
    // and the admin GUI; they no longer control offer generation — custom enchants can
    // appear ONLY on the tier III offer, governed by customTier3Chance below).
    private int slot1CustomChance = 10;
    private int slot2CustomChance = 20;
    private int slot3CustomChance = 30;

    /**
     * Шанс (в процентах) того, что на 3-м (максимальном) тире стола
     * зачарований вместо ванильного зачарования будет предложено кастомное.
     * К книжным полкам прибавляется +0.2% за полку (до +3% при 15 полках),
     * НО 0% в конфиге остаётся 0%: бонус от полок не «включает» кастомные
     * чары, которые отключены админом.
     * Ровно ОДИН бросок на зачарование (второго «бонусного» броска нет),
     * безусловного fallback'а при отсутствии ванильных чар тоже.
     * Кастомные зачарования на столе выпадают ТОЛЬКО на 3-м тире и только 1 уровня.
     */
    private double customTier3Chance = 3.0;

    /**
     * Переливание названий зачарований (shimmer animation).
     * По умолчанию ВЫКЛЮЧЕНО (false), так как частое обновление предметов в руке
     * вызывает эффект дёргания/переэкипировки оружия в клиенте Minecraft.
     */
    private boolean loreAnimation = false;

    /**
     * Зачарования, которые НИКОГДА не выпадают на столе зачарований
     * (даже если в enachants.yml у них стоит enchanttable: true).
     * По умолчанию — "trench" (Экскаватор гномов, копание 3x3).
     */
    private final Set<String> tableDisabledIds = new HashSet<>(Collections.singletonList("trench"));

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
            this.customTier3Chance = clampPercent(config.getDouble("table_settings.custom_tier3_chance", 3.0));
            this.loreAnimation = config.getBoolean("table_settings.lore_animation", false);
            me.aquaenchants.util.LoreAnimationManager.setAnimationEnabled(this.loreAnimation);

            tableDisabledIds.clear();
            List<String> ids = config.getStringList("table_settings.table_disabled_ids");
            if (ids.isEmpty()) {
                tableDisabledIds.add("trench");
            } else {
                for (String id : ids) {
                    if (id == null) continue;
                    String trimmed = id.trim().toLowerCase(Locale.ROOT);
                    if (!trimmed.isEmpty()) tableDisabledIds.add(trimmed);
                }
                // "trench" (Экскаватор гномов 3x3) защищён всегда
                tableDisabledIds.add("trench");
            }
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
            config.set("table_settings.custom_tier3_chance", customTier3Chance);
            config.set("table_settings.lore_animation", loreAnimation);
            config.set("table_settings.table_disabled_ids", new ArrayList<>(tableDisabledIds));
            plugin.saveConfig();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save table_settings: " + e.getMessage());
        }
    }

    private static double clampPercent(double value) {
        if (value < 0) return 0;
        if (value > 100) return 100;
        return value;
    }

    /**
     * Calculates the extra bonus enchant chance based on bookshelf count.
     */
    public int calculateBookshelfBonusChance(int bookshelves) {
        int power = Math.max(0, Math.min(15, bookshelves));
        return minBonusChance + (int) Math.round((maxBonusChance - minBonusChance) * (power / 15.0));
    }

    /**
     * Calculates the custom enchantment roll chance for a given slot index (0, 1, 2).
     *
     * Кастомные зачарования выпадают ТОЛЬКО на 3-м тире (slotIndex == 2) — на 1-м и 2-м
     * тире они появляться не должны вовсе (иначе игроки получают их почти бесплатно).
     *
     * Формула: custom_tier3_chance + 0.2% за книжную полку (до +3% при 15 полках).
     * FIX: если в конфиге стоит 0% — результат всегда 0%: бонус от полок не
     * «включает» отключённые кастомные чары (раньше 0% с 15 полками давало 3%).
     */
    public double calculateSlotCustomChance(int slotIndex, int bookshelves) {
        if (slotIndex != 2) {
            return 0.0;
        }
        if (customTier3Chance <= 0.0) {
            return 0.0;
        }
        int power = Math.max(0, Math.min(15, bookshelves));
        return clampPercent(customTier3Chance + (power * 0.2));
    }

    /**
     * Ids кастомных зачарований (в нижнем регистре), которые не должны выпадать на столе.
     */
    public Set<String> getTableDisabledIds() {
        return Collections.unmodifiableSet(tableDisabledIds);
    }

    public boolean isTableDisabled(String enchantId) {
        if (enchantId == null) return false;
        return tableDisabledIds.contains(enchantId.toLowerCase(Locale.ROOT));
    }

    public double getCustomTier3Chance() {
        return customTier3Chance;
    }

    public void setCustomTier3Chance(double customTier3Chance) {
        this.customTier3Chance = clampPercent(customTier3Chance);
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

    /**
     * @deprecated кастомные чары выпадают только на 3-м тире; значение слота I больше не используется.
     */
    @Deprecated
    public int getSlot1CustomChance() {
        return slot1CustomChance;
    }

    /**
     * @deprecated см. {@link #getSlot1CustomChance()}
     */
    @Deprecated
    public void setSlot1CustomChance(int slot1CustomChance) {
        this.slot1CustomChance = Math.max(0, Math.min(100, slot1CustomChance));
    }

    /**
     * @deprecated см. {@link #getSlot1CustomChance()}
     */
    @Deprecated
    public int getSlot2CustomChance() {
        return slot2CustomChance;
    }

    /**
     * @deprecated см. {@link #getSlot1CustomChance()}
     */
    @Deprecated
    public void setSlot2CustomChance(int slot2CustomChance) {
        this.slot2CustomChance = Math.max(0, Math.min(100, slot2CustomChance));
    }

    /**
     * @deprecated см. {@link #getSlot1CustomChance()}
     */
    @Deprecated
    public int getSlot3CustomChance() {
        return slot3CustomChance;
    }

    /**
     * @deprecated см. {@link #getSlot1CustomChance()}
     */
    @Deprecated
    public void setSlot3CustomChance(int slot3CustomChance) {
        this.slot3CustomChance = Math.max(0, Math.min(100, slot3CustomChance));
    }

    public boolean isLoreAnimation() {
        return loreAnimation;
    }

    public void setLoreAnimation(boolean loreAnimation) {
        this.loreAnimation = loreAnimation;
        me.aquaenchants.util.LoreAnimationManager.setAnimationEnabled(loreAnimation);
    }
}
