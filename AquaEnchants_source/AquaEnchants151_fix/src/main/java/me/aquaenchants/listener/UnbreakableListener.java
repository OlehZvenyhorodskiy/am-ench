package me.aquaenchants.listener;

import me.aquaenchants.AquaEnchatsPlugin;
import me.aquaenchants.enchant.CustomEnchant;
import me.aquaenchants.enchant.EnchantManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class UnbreakableListener implements Listener {

    private final AquaEnchatsPlugin plugin;
    private final EnchantManager enchantManager;

    public UnbreakableListener(AquaEnchatsPlugin plugin, EnchantManager enchantManager) {
        this.plugin = plugin;
        this.enchantManager = enchantManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) return;

        Map<CustomEnchant, Integer> enchants = enchantManager.getEnchantmentsOnItem(item);
        if (enchants == null || enchants.isEmpty()) return;

        for (Map.Entry<CustomEnchant, Integer> e : enchants.entrySet()) {
            CustomEnchant ench = e.getKey();
            Integer lvl = e.getValue();
            if (ench == null || lvl == null || lvl <= 0) continue;

            String id = ench.getId();
            if (id == null) continue;
            if ("unbreakable".equalsIgnoreCase(id)) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
