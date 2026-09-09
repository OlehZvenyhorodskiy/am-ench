package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Random;

/**
 * Обработчик зачарования inquisitive (Добытчик опыта) для убийств мобов.
 */
public class InquisitiveListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Random random = new Random();

    public InquisitiveListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim == null) {
            return;
        }

        Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }

        ItemStack weapon = killer.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType().isAir()) {
            return;
        }

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(weapon);
        if (enchants == null || enchants.isEmpty()) {
            return;
        }

        CustomEnchant inquisitive = null;
        int level = 0;

        for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
            CustomEnchant ench = entry.getKey();
            Integer lvl = entry.getValue();
            if (ench == null || lvl == null || lvl <= 0) continue;

            if ("inquisitive".equalsIgnoreCase(ench.getId())) {
                inquisitive = ench;
                if (lvl > level) {
                    level = lvl;
                }
            }
        }

        if (inquisitive == null || level <= 0) {
            return;
        }

        EnchantLevel data = inquisitive.getLevel(level);
        if (data == null) {
            return;
        }

        int chance = data.getChance();
        plugin.debug("[inquisitive] killer=" + killer.getName() + " level=" + level + " chance=" + chance);

        double roll = random.nextDouble() * 100.0;
        double chanceClamped = Math.max(0.0, Math.min(100.0, chance));
        if (roll > chanceClamped) {
            plugin.debug("[inquisitive] roll=" + roll + " > chance=" + chanceClamped + ", пропуск");
            return;
        }

        int baseExp = event.getDroppedExp();
        if (baseExp <= 0) {
            plugin.debug("[inquisitive] baseExp <= 0, nothing to modify");
            return;
        }

        double multiplier = 1.0 + 0.25 * level; // %exp% * (1.0 + 0.25 * %level%)
        int newExp = (int) Math.round(baseExp * multiplier);

        plugin.debug("[inquisitive] baseExp=" + baseExp + ", newExp=" + newExp + ", mult=" + multiplier + ", level=" + level);
        event.setDroppedExp(newExp);
    }
}
