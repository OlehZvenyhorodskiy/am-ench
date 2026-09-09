package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantLevel;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Зачарование decapitation:
 *  - тип KILL_PLAYER;
 *  - при убийстве игрока с шансом из конфига дропает его голову;
 *  - кулдаун также берётся из конфига уровней;
 *  - уровни складываются через наковальню, логика общая в AnvilListener.
 *
 * Для текстуры головы достаточно стандартного setOwningPlayer(victim):
 * плагин SkinsRestorer, если установлен, уже отвечает за корректные скины offline‑игроков.
 */
public class DecapitationListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;
    private final Random random = new Random();

    // Кулдауны по убийце
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public DecapitationListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
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

        CustomEnchant decap = null;
        int level = 0;

        for (Map.Entry<CustomEnchant, Integer> entry : enchants.entrySet()) {
            CustomEnchant ench = entry.getKey();
            Integer lvl = entry.getValue();
            if (ench == null || lvl == null || lvl <= 0) {
                continue;
            }

            if ("decapitation".equalsIgnoreCase(ench.getId())) {
                decap = ench;
                if (lvl > level) {
                    level = lvl;
                }
            }
        }

        if (decap == null || level <= 0) {
            return;
        }

        EnchantLevel cfg = decap.getLevel(level);
        int chance = 100;
        int cooldownSec = 0;
        if (cfg != null) {
            if (cfg.getChance() > 0) {
                chance = cfg.getChance();
            }
            if (cfg.getCooldown() > 0) {
                cooldownSec = cfg.getCooldown();
            }
        }

        if (chance < 100) {
            double roll = random.nextDouble() * 100.0;
            if (roll > chance) {
                plugin.debug("[decapitation] roll failed: roll=" + roll + " chance=" + chance);
                return;
            }
        }

        if (cooldownSec > 0) {
            long now = System.currentTimeMillis();
            long cdMs = cooldownSec * 1000L;
            UUID killerId = killer.getUniqueId();
            Long last = cooldowns.get(killerId);
            if (last != null && now - last < cdMs) {
                plugin.debug("[decapitation] cooldown active for " + killer.getName());
                return;
            }
            cooldowns.put(killerId, now);
        }

        // Создаём голову жертвы
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = null;
        if (head.getItemMeta() instanceof SkullMeta) {
            meta = (SkullMeta) head.getItemMeta();
        }
        if (meta != null) {
            try {
                // Use full player profile so that plugins like SkinsRestorer skins are respected
                org.bukkit.profile.PlayerProfile profile = victim.getPlayerProfile();
                meta.setOwnerProfile(profile);
            } catch (NoSuchMethodError ignored) {
                // Fallback for older API versions
                meta.setOwningPlayer(victim);
            }
            meta.setDisplayName("§rГолова " + victim.getName());
            head.setItemMeta(meta);
        }

        plugin.debug("[decapitation] dropping head of " + victim.getName() + " for " + killer.getName());
        event.getDrops().add(head);
    }
}
