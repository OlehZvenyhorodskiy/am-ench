package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EffectConfig;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.UUID;

/**
 * Обработчик правого клика с элитрами и зачарованием momentum.
 * Увеличивает скорость полёта при использовании фейерверков.
 */
public class MomentumListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Random random = new Random();
    private final Map<UUID, Long> momentumCooldowns = new HashMap<>();

    public MomentumListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) return;

        // Проверяем, что в руке именно фейерверк (как в условии %player is holding% = FIREWORK_ROCKET)
        ItemStack hand = event.getItem();
        if (hand == null || hand.getType() != Material.FIREWORK_ROCKET) {
            return;
        }

        // Проверяем, что на груди элитры
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) {
            return;
        }

        // Ищем кастомное зачарование momentum на элитрах
        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(chest);
        if (enchants == null || enchants.isEmpty()) {
            return;
        }

        CustomEnchant momentum = null;
        int level = 0;
        for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
            CustomEnchant ench = e.getKey();
            Integer lvl = e.getValue();
            if (ench == null || lvl == null || lvl <= 0) continue;
            String id = ench.getId();
            if (id == null) continue;
            if ("momentum".equalsIgnoreCase(id)) {
                momentum = ench;
                level = lvl;
                break;
            }
        }

        if (momentum == null || level <= 0) {
            return;
        }

        EnchantLevel data = momentum.getLevel(level);
        if (data == null) {
            return;
        }

        int chance = data.getChance();
        long now = System.currentTimeMillis();
        int cooldownSec = data.getCooldown();
        long cooldownMs = cooldownSec * 1000L;

        plugin.debug("[momentum] player=" + player.getName()
                + " level=" + level
                + " chance=" + chance
                + " cooldown=" + cooldownSec + "s");

        // Проверка кулдауна
        UUID uuid = player.getUniqueId();
        Long last = momentumCooldowns.get(uuid);
        if (last != null && now - last < cooldownMs) {
            long left = cooldownMs - (now - last);
            plugin.debug("[momentum] cooldown active for " + player.getName() + " (" + left + " ms left)");
            return;
        }

        // Проверка шанса
        if (chance > 0 && random.nextInt(100) >= chance) {
            plugin.debug("[momentum] roll failed for " + player.getName());
            return;
        }

        momentumCooldowns.put(uuid, now);

        // Выполняем эффекты (нас интересует POTION:SPEED:...)
        for (EffectConfig ec : data.getEffects()) {
            if (ec == null) continue;
            String eff = ec.getId();
            if (eff == null) continue;
            String trimmed = eff.trim();
            String upper = trimmed.toUpperCase(Locale.ROOT);

            if (upper.startsWith("POTION:SPEED")) {
                // Формат: POTION:SPEED:amp:durationTicksOrSeconds(в нашем конфиге duration в тиках?)
                String[] parts = trimmed.split(":", 4);
                int amplifier = 0;
                int duration = 40; // по умолчанию 2 секунды (40 тиков)
                if (parts.length >= 3) {
                    try {
                        amplifier = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException ignored) {}
                }
                if (parts.length >= 4) {
                    try {
                        duration = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException ignored) {}
                }

                // В твоём конфиге значения похожи на секунды -> умножим на 20 для тиков.
                int ticks = duration * 20;
                if (ticks <= 0) ticks = 40;

                PotionEffect effect = new PotionEffect(PotionEffectType.SPEED, ticks, amplifier, true, true, true);
                player.addPotionEffect(effect);
                plugin.debug("[momentum] applied SPEED to " + player.getName()
                        + " amplifier=" + amplifier
                        + " durationTicks=" + ticks);
            }
        }
    }
}
