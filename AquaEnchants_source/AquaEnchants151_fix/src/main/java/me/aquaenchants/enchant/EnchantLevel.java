package me.aquaenchants.enchant;

import java.util.List;

public class EnchantLevel {

    private final int level;
    private final int chance;
    private final int progress;
    private final int cooldown;
    private final java.util.List<EffectConfig> effects;

    public EnchantLevel(int level, int chance, List<EffectConfig> effects, int progress, int cooldown) {
        this.level = level;
        this.chance = chance;
        this.effects = effects;
        this.progress = progress;
        this.cooldown = cooldown;
    }

    public int getLevel() {
        return level;
    }

    public int getChance() {
        return chance;
    }

    public int getProgress() {
        return progress;
    }

    public int getCooldown() {
        return cooldown;
    }

    public java.util.List<EffectConfig> getEffects() {
        return effects;
    }
}
