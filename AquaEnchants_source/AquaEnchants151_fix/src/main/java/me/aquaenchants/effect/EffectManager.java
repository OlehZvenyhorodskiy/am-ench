package me.aquaenchants.effect;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.util.ProtectedBlockUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Управляет выполнением эффектов кастомных зачарований.
 */
public class EffectManager {

    private final SmeltEffect smeltEffect = new SmeltEffect();
    private final LineMineEffect lineMineEffect = new LineMineEffect();
    private final BreakBlockEffect breakBlockEffect = new BreakBlockEffect();
    private final BreakTreeEffect breakTreeEffect = new BreakTreeEffect();
    private final TelepathyEffect telepathyEffect = new TelepathyEffect();
    private final ExpEffect expEffect = new ExpEffect();
    private final Random random = new Random();

    public EffectManager() { }

    public EffectManager(AquaEnchatsPlugin plugin) {
        this();
    }

    /**
     * Старое поведение: выполнить все эффекты уровня подряд.
     */
    public void handleMiningEnchant(Player player,
                                    ItemStack tool,
                                    CustomEnchant enchant,
                                    int level,
                                    BlockBreakEvent event) {
        if (event == null || ProtectedBlockUtil.isProtected(event.getBlock())) return;

        EnchantLevel lvl = enchant.getLevel(level);
        if (lvl == null) return;

        List<EffectConfig> effects = lvl.getEffects();
        if (effects == null || effects.isEmpty()) return;

        for (EffectConfig ec : effects) {
            EffectType type = EffectType.fromString(ec.getId());
            if (type == null) continue;

            AquaEnchatsPlugin.getInstance().debug("[DEBUG]  effect=" + ec.getId());

            switch (type) {
                case SMELT: {
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> SMELT (chance=" + lvl.getChance() + ")");
                    double chance = Math.max(0.0, Math.min(100.0, lvl.getChance()));
                    if (random.nextDouble() * 100.0 <= chance) {
                        smeltEffect.handle(new EffectContext(player, tool, event.getBlock(), event), ec);
                    }
                    break;
                }
                case LINEMINE: {
                    int depth = Math.max(1, Math.min(5, level));
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> LINEMINE depth=" + depth);
                    lineMineEffect.handle(new EffectContext(player, tool, event.getBlock(), event), depth);
                    break;
                }
                case BREAK_BLOCK: {
                    int lvlDepth = Math.max(1, Math.min(5, level));
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> BREAK_BLOCK staged level=" + lvlDepth);
                    int delayTicks = enchant.getBreakBlockDelayTicks();
                    breakBlockEffect.handleStaged(new EffectContext(player, tool, event.getBlock(), event), lvlDepth, delayTicks);
                    break;
                }
                case BREAK_TREE: {
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> BREAK_TREE");
                    breakTreeEffect.handle(new EffectContext(player, tool, event.getBlock(), event), level);
                    break;
                }
                case LAVA: {
                    // Зарезервировано под визуальный эффект
                    break;
                }
                case TP_DROPS: {
                    // Телепортация дропа в инвентарь / к игроку
                    telepathyEffect.handle(new EffectContext(player, tool, event.getBlock(), event), ec);
                    break;
                }
                case EXP: {
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> EXP (chance=" + lvl.getChance() + ", level=" + level + ")");
                    double chance = Math.max(0.0, Math.min(100.0, lvl.getChance()));
                    if (random.nextDouble() * 100.0 <= chance) {
                        expEffect.handle(new EffectContext(player, tool, event.getBlock(), event), ec, level);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    /**
     * Новое поведение: выполнить только те эффекты, которые соответствуют указанному шагу.
     * step: SMELT | LINEMINE | BREAK_BLOCK | LAVA
     */
    public void handleMiningEnchantStep(Player player,
                                        ItemStack tool,
                                        CustomEnchant enchant,
                                        int level,
                                        BlockBreakEvent event,
                                        String step) {
        if (step == null || step.trim().isEmpty()) {
            // Если шаг не задан – старое поведение.
            handleMiningEnchant(player, tool, enchant, level, event);
            return;
        }
        String phase = step.trim().toUpperCase(java.util.Locale.ROOT);

        if (event == null || ProtectedBlockUtil.isProtected(event.getBlock())) return;

        EnchantLevel lvl = enchant.getLevel(level);
        if (lvl == null) return;

        List<EffectConfig> effects = lvl.getEffects();
        if (effects == null || effects.isEmpty()) return;

        for (EffectConfig ec : effects) {
            EffectType type = EffectType.fromString(ec.getId());
            if (type == null) continue;

            AquaEnchatsPlugin.getInstance().debug("[DEBUG]   considering=" + ec.getId() + " -> " + type.name());

            boolean allowed = false;
            if (phase.equals(type.name())) {
                allowed = true;
            } else if ("BREAK_BLOCK".equals(phase)) {
                // Для шага BREAK_BLOCK разрешаем сам BREAK_BLOCK и старый LINEMINE, если он есть.
                if (type == EffectType.BREAK_BLOCK || type == EffectType.LINEMINE) {
                    allowed = true;
                }
            }
            AquaEnchatsPlugin.getInstance().debug("[DEBUG]    allowed=" + allowed + " for=" + type.name());
            if (!allowed) continue;

            switch (type) {
                case SMELT: {
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> SMELT (chance=" + lvl.getChance() + ")");
                    double chance = Math.max(0.0, Math.min(100.0, lvl.getChance()));
                    if (random.nextDouble() * 100.0 <= chance) {
                        smeltEffect.handle(new EffectContext(player, tool, event.getBlock(), event), ec);
                    }
                    break;
                }
                case LINEMINE: {
                    int depth = Math.max(1, Math.min(5, level));
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> LINEMINE depth=" + depth);
                    lineMineEffect.handle(new EffectContext(player, tool, event.getBlock(), event), depth);
                    break;
                }
                case BREAK_BLOCK: {
                    int lvlDepth = Math.max(1, Math.min(5, level));
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> BREAK_BLOCK staged level=" + lvlDepth);
                    int delayTicks = enchant.getBreakBlockDelayTicks();
                    breakBlockEffect.handleStaged(new EffectContext(player, tool, event.getBlock(), event), lvlDepth, delayTicks);
                    break;
                }
                case BREAK_TREE: {
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> BREAK_TREE");
                    breakTreeEffect.handle(new EffectContext(player, tool, event.getBlock(), event), level);
                    break;
                }
                case LAVA: {
                    // Зарезервировано
                    break;
                }
                case TP_DROPS: {
                    // Телепортация дропа в инвентарь / к игроку (по шагам)
                    telepathyEffect.handle(new EffectContext(player, tool, event.getBlock(), event), ec);
                    break;
                }
                case EXP: {
                    AquaEnchatsPlugin.getInstance().debug("[DEBUG]   -> EXP (chance=" + lvl.getChance() + ", level=" + level + ")");
                    double chance = Math.max(0.0, Math.min(100.0, lvl.getChance()));
                    if (random.nextDouble() * 100.0 <= chance) {
                        expEffect.handle(new EffectContext(player, tool, event.getBlock(), event), ec, level);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }
}
