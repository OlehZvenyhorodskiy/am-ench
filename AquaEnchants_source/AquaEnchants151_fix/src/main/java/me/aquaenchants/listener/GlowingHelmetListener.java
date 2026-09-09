package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;

/**
 * Зачарование glowing:
 * Если на шлеме есть это зачарование, игрок получает бесконечное ночное зрение.
 * При снятии шлема/пропаже зачарования эффект снимается.
 */
public class GlowingHelmetListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;

    public GlowingHelmetListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshPlayerLater(event.getPlayer(), 1L);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        refreshPlayerLater(event.getPlayer(), 1L);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        refreshPlayerLater(player, 1L);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onItemBreak(PlayerItemBreakEvent event) {
        Player player = event.getPlayer();
        refreshPlayerLater(player, 1L);
    }

    private void refreshPlayerLater(Player player, long delayTicks) {
        if (player == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshPlayer(player), delayTicks);
    }

    private void refreshPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        boolean hasGlowing = hasGlowingHelmet(player);
        if (hasGlowing) {
            applyNightVision(player);
        } else {
            removeNightVision(player);
        }
    }

    private boolean hasGlowingHelmet(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet == null || helmet.getType().isAir()) {
            return false;
        }
        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(helmet);
        if (enchants == null || enchants.isEmpty()) {
            return false;
        }
        for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
            CustomEnchant ench = e.getKey();
            Integer lvl = e.getValue();
            if (ench == null || lvl == null || lvl <= 0) continue;
            String id = ench.getId();
            if (id == null) continue;
            if ("glowing".equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private void applyNightVision(Player player) {
        PotionEffectType type = PotionEffectType.NIGHT_VISION;
        if (type == null) {
            return;
        }

        /*
         * Для новых версий Spigot (1.19.4+ / 1.21.x) бесконечный эффект
         * обозначается duration = -1. Клиент отображает его иконкой ∞.
         */
        int durationTicks = -1;
        int amplifier = 0;

        PotionEffect current = player.getPotionEffect(type);
        if (current != null && current.getAmplifier() >= amplifier) {
            // Уже есть активный эффект ночного зрения не слабее нашего
            return;
        }

        PotionEffect effect = new PotionEffect(type, durationTicks, amplifier, true, true, true);
        player.addPotionEffect(effect);
    }

    private void removeNightVision(Player player) {
        PotionEffectType type = PotionEffectType.NIGHT_VISION;
        if (type != null) {
            player.removePotionEffect(type);
        }
    }
}
