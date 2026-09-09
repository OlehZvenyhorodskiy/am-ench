package me.aquaenchants.enchant;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class CustomEnchant {

    private final String id;
    private final String display;
    private final String description;
    private final String appliesTo;
    private final EnchantType type;
    private final String group;
    private final Set<ToolCategory> applies;
    private final boolean enchantTableEnabled;
    private int enchantTableChance;
    private final boolean villagerEnabled;
    private final int villagerChance;
    private final Map<Integer, EnchantLevel> levels;
    // Delay in ticks between BREAK_BLOCK stages (for trench-like enchants).
    // Defaults to 1 tick if not configured.
    private final int breakBlockDelayTicks;
    private final int powerPercent;

    public CustomEnchant(String id,
                         String display,
                         String description,
                         String appliesTo,
                         EnchantType type,
                         String group,
                         Set<ToolCategory> applies,
                         boolean enchantTableEnabled,
                         int enchantTableChance,
                         boolean villagerEnabled,
                         int villagerChance,
                         Map<Integer, EnchantLevel> levels,
                         int breakBlockDelayTicks,
                         int powerPercent) {
        this.id = id;
        this.display = display;
        this.description = description;
        this.appliesTo = appliesTo;
        this.type = type;
        this.group = group;
        this.applies = Collections.unmodifiableSet(applies);
        this.enchantTableEnabled = enchantTableEnabled;
        this.enchantTableChance = enchantTableChance;
        this.villagerEnabled = villagerEnabled;
        this.villagerChance = villagerChance;
        this.levels = Collections.unmodifiableMap(levels);
        this.breakBlockDelayTicks = breakBlockDelayTicks;
        this.powerPercent = powerPercent;
    }

    public String getId() {
        return id;
    }

    public String getDisplay() {
        return display;
    }

    public String getDisplayName() {
        return display;
    }

    public String getDescription() {
        return description;
    }

    public String getAppliesTo() {
        return appliesTo;
    }

    public EnchantType getType() {
        return type;
    }

    public String getGroup() {
        return group;
    }

    public Set<ToolCategory> getApplies() {
        return applies;
    }

    public boolean isEnchantTableEnabled() {
        return enchantTableEnabled;
    }

    public int getEnchantTableChance() {
        return enchantTableChance;
    }

    public void setEnchantTableChance(int chance) {
        this.enchantTableChance = Math.max(0, Math.min(100, chance));
    }

    public boolean isVillagerEnabled() {
        return villagerEnabled;
    }

    public int getVillagerChance() {
        return villagerChance;
    }

    public Map<Integer, EnchantLevel> getLevels() {
        return levels;
    }

    public EnchantLevel getLevel(int level) {
        return levels.get(level);
    }

    public int getPowerPercent() {
        return powerPercent;
    }

    /**
     * Delay in ticks between BREAK_BLOCK stages for this enchant.
     * Only used for staged BREAK_BLOCK effects (e.g. trench), but configurable
     * per-enchant so you can experiment in enachants.yml.
     */
    public int getBreakBlockDelayTicks() {
        return breakBlockDelayTicks;
    }
}