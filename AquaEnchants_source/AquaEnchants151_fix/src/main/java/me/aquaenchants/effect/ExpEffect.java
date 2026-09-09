package me.aquaenchants.effect;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.EffectConfig;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Дополнительный опыт с руд.
 * Увеличивает количество опыта, выпадающего из блока, в зависимости от уровня зачарования.
 */
public class ExpEffect {

    /**
     * @param level уровень зачарования (1..N)
     */
    public void handle(EffectContext context, EffectConfig config, int level) {
        if (context == null) return;
        BlockBreakEvent event = context.getBlockBreakEvent();
        if (event == null) return;

        int baseExp = event.getExpToDrop();
        if (baseExp <= 0) {
            AquaEnchatsPlugin.getInstance().debug("[DEBUG] EXP: baseExp <= 0, nothing to modify (level=" + level + ")");
            return;
        }

        double multiplier;
        switch (level) {
            case 1:
                multiplier = 1.2;
                break;
            case 2:
                multiplier = 1.4;
                break;
            case 3:
                multiplier = 1.6;
                break;
            case 4:
                multiplier = 1.8;
                break;
            default:
                multiplier = 2.0;
                break;
        }

        int newExp = (int) Math.round(baseExp * multiplier);
        if (newExp <= baseExp) {
            newExp = baseExp + 1;
        }

        AquaEnchatsPlugin.getInstance().debug("[DEBUG] EXP effect: level=" + level + ", baseExp=" + baseExp + ", newExp=" + newExp + ", multiplier=" + multiplier);
        event.setExpToDrop(newExp);
    }
}
